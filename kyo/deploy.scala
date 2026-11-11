#!/usr/bin/env bash
//usr/bin/env true; exec "$(dirname "$0")/scala-lib/run" "$0" "$@" # bash runs this line; Scala sees a comment
// Evaluates nixosConfigurations.<name> (hosts/<name>/) on this machine, then builds
// and activates it on the target over ssh.
// The machine is reached at arturas@<name>.lan; override with TARGET=user@host.
// Set UPDATE=1 to update the flake inputs (nixpkgs) first; commit flake.lock afterwards.
// Set BUILD_LOCAL=1 to build on this machine instead (worth it only when something
// has to be compiled; otherwise everything comes from cache.nixos.org anyway, and
// the target already has most of it).
// After switch or test, it sets up the router (./router.scala <name>).
//
// It does what `nixos-rebuild --build-host/--target-host --sudo` does, step by step,
// so it can check the sudo password before building (nixos-rebuild only finds out
// it's wrong after the build) and say which step failed and what that left behind.
// It asks for the password only when it needs root: to activate a system the target
// doesn't already run (or boot into, for boot), or to read a Plasma panel layout the
// target doesn't export yet (see modules/desktop/plasma-panels.nix).
// Usage: --help.
//> using file scala-lib

import kyo.*
import nixosconf.lib.{*, given}

object Main extends ScriptApp[DeployOptions]:
  override def name: String = "deploy.scala"
  def description: String =
    "Evaluates the host's configuration here, builds it on the machine (or here, " +
      "BUILD_LOCAL=1) and activates it there; after switch or test, sets the router up.\n\n" +
      DeployArgs.help

  def program(options: DeployOptions, args: Chunk[String]): Int < (Script & Scope) =
    direct {
      val root = Repo.root.now
      val known = KnownHosts.discover(root).now
      val parsed = DeployArgs.parse(known, args).now
      Scope.run(Deploy.session(root, parsed).map(_.run)).now
      0
    }

/** Files generated on the machine itself (e.g. by nixos-generate-config). The
  * machine's /etc/nixos is their source of truth; they're copied from there into
  * hosts/<name>/ before every build, and committed like everything else.
  */
val machineFiles = Seq("hardware-configuration.nix")

/** Each user's Plasma panel layout (see modules/desktop/plasma-panels.nix), in
  * ~/.config, and exported (without sudo) in [[plasmaExport]]/<user>/.
  */
val plasmaFiles = Seq("plasma-org.kde.plasma.desktop-appletsrc", "plasmashellrc")
val plasmaExport = "/var/lib/plasma-panels"

/** A word for a POSIX shell, quoted (what bash's `printf %q` is for). */
def shellQuote(word: String): String =
  if word.nonEmpty && word.forall(c => c.isLetterOrDigit || "@%+=:,./_-".contains(c)) then word
  else "'" + word.replace("'", "'\\''") + "'"

/** How to become root on the target: through sudo with this password, or already
  * root there.
  */
enum Root:
  case Already
  case Sudo(password: String)

object Deploy:

  /** One ssh connection for the whole run (including nix's own ssh), so ssh only
    * connects, and asks for anything, once. When it ends, the files updated from the
    * machine are listed, so that isn't lost under the build output (or a failure).
    */
  def session(root: Path, args: DeployArgs)(using Frame): Deploy < (Sync & Scope & PathWrite) =
    direct {
      val target = args.destination
      val tmp = Path.tempDir("deploy").now
      val sshOpts = Seq("-o", "ControlMaster=auto", "-o", show"ControlPath=$tmp/ssh-%C", "-o", "ControlPersist=60")
      Scope
        .ensure(
          // Nothing to do if it fails: the master exits by itself after ControlPersist.
          Abort.run[Stop](
            Proc.status(Command(Seq("ssh") ++ sshOpts ++ Seq("-O", "exit", target)*), root, Out.Discard, Out.Discard)
          )
        )
        .now
      val uncommitted = Scope
        .acquireRelease(AtomicRef.init(Chunk.empty[String]))(ref =>
          direct {
            val files = ref.get.now
            if files.nonEmpty then
              Console.printLine(show"\nUpdated from $target, needs committing:").now
              Kyo.foreachDiscard(files)(f => Console.printLine(show"  $f")).now
          }
        )
        .now
      Deploy(root, args, target, sshOpts, uncommitted)
    }

final case class Deploy(
    root: Path,
    args: DeployArgs,
    target: String,
    sshOpts: Seq[String],
    uncommitted: AtomicRef[Chunk[String]]
):
  val host: String = args.host.name
  val hostDir: Path = root / "hosts" / host
  val flake: FlakeRef = FlakeRef.of(root)
  /** For nix's own ssh. */
  val nixEnv: Map[String, String] = Map("NIX_SSHOPTS" -> sshOpts.mkString(" "))
  val nothingChanged = show"Nothing was changed on $host."

  def onTarget(command: String*): Command = Command(Seq("ssh") ++ sshOpts ++ Seq(target) ++ command*)

  /** The error is printed here, before the session's end lists the files updated
    * from the machine.
    */
  def run(using Frame): Unit < Script =
    Abort.recover[Stop] {
      case Fail(message, code) => Console.printLineErr(show"error: $message").andThen(Abort.fail(Exit(code)))
      case other               => Abort.fail(other)
    }(steps)

  def steps(using Frame): Unit < Script =
    direct {
      // ssh exits with 255 on connection/auth errors.
      val connected = Proc.status(onTarget("true"), root).now
      unless(connected == 0)(
        fail(show"could not connect to $target. Is it on and on the network? Try: ssh $target")
      ).now
      Kyo.foreachDiscard(machineFiles)(copyMachineFile).now
      // Asked for (and checked) when first needed, then kept.
      val rootAccess = Async.memoize(becomeRoot).now
      if (hostDir / "plasma").isDirectory.now then copyPlasmaFiles(rootAccess).now
      if args.update then
        Proc
          .succeedOr(Nix("flake", "update", "--flake", flake.unwrap), root)(_ =>
            show"updating flake.lock failed (see above). $nothingChanged"
          )
          .now
        Console.printLine("Updated flake.lock; commit it.").now
      // The repo is public: refuse to deploy (and so, likely, to push next) a plaintext
      // secret. After the machine's files above are copied in, as they get committed
      // too.
      Proc
        .succeedOr(Command((root / "check-secrets.scala").toString), root)(_ =>
          show"a secret would be published (see above). $nothingChanged"
        )
        .now
      Console.printLineErr("Evaluating the configuration...").now
      val toplevel = Nix
        .evalJson[Toplevel](
          Installable(
            flake,
            Seq("system", "build", "toplevel").foldLeft(args.host.config(flake).attribute)((path, name) =>
              path / AttrName.make.orThrow(name)
            )
          ),
          "t: { drv = t.drvPath; out = t.outPath; }",
          root
        )(show"the configuration doesn't evaluate (see above). $nothingChanged")
        .now
      args.action match
        case Action.Build =>
          val system = build(toplevel.drv).now
          Console.printLine(show"Done: $system is built and on $target.").now
        case action =>
          if alreadyThere(action, toplevel.out).now then
            Console.printLine(show"Nothing to do: $host already ${action.already} ${toplevel.out}.").now
            setUpRouter(action).now
          else
            // The password before the build, so a typo doesn't cost one.
            val access = rootAccess.now
            val system = build(toplevel.drv).now
            activate(access, action, system).now
    }

  /** Whether the target already is where `action` would take it: running `system`
    * (switch, test, dry-activate) and booting into it (switch, boot). Then there's
    * nothing to activate, and no need for root.
    */
  def alreadyThere(action: Action, system: String)(using Frame): Boolean < Proc.Effects =
    Proc
      .output(onTarget("readlink -f /run/current-system; readlink -f /nix/var/nix/profiles/system"), root)
      .map {
        case (0, out) =>
          out.linesIterator.toList match
            case List(current, booted) =>
              val runs = current == system
              val boots = booted == system
              action match
                case Action.Switch                    => runs && boots
                case Action.Test | Action.DryActivate => runs
                case Action.Boot                      => boots
                case Action.Build                     => false
            case _ => false
        case _ => false
      }

  extension (action: Action)
    /** What the target already does with a system when [[alreadyThere]]. */
    def already: String = action match
      case Action.Switch => "runs and boots into"
      case Action.Boot   => "boots into"
      case _             => "runs"

  /** The router's firewall, with the services the host now runs (see router.scala),
    * after switch or test.
    */
  def setUpRouter(action: Action)(using Frame): Unit < Proc.Effects =
    when(action == Action.Switch || action == Action.Test)(
      Proc.succeedOr(Command((root / "router.scala").toString, host), root)(_ =>
        show"$host is switched, but setting up the router failed (see above); retry with: ./router.scala $host"
      )
    )

  /** Lists `file` for committing if it's new or different from the last commit. */
  def noteIfChanged(file: Path)(using Frame): Unit < (Proc.Effects & PathRead) =
    direct {
      val rel = file.toString.stripPrefix(root.toString + "/")
      val changed = Vcs.changed(root, Seq(rel)).now
      if changed.nonEmpty then uncommitted.updateAndGet(_.append(rel)).unit.now
    }

  /** Runs `command` with its output into `dest` (through a temporary file, so a
    * failure leaves `dest` as it was); whether it succeeded.
    */
  def into(
      dest: Path,
      command: Command,
      stdin: Maybe[String] = Absent,
      stderr: Out = Out.Inherit
  )(using Frame): Boolean < (Proc.Effects & PathWrite) =
    direct {
      val tmp = Path(dest.toString + ".tmp")
      val code = Proc.status(command, root, Out.ToFile(tmp), stderr, stdin = stdin).now
      if code == 0 then
        tmp.move(dest, Path.MoveOptions(replace = Path.Replace.Existing)).now
        true
      else
        val _ = tmp.remove.now
        false
    }

  def copyMachineFile(file: String)(using Frame): Unit < Script =
    direct {
      val dest = hostDir / file
      if into(dest, onTarget(show"cat /etc/nixos/$file")).now then noteIfChanged(dest).now
      else
        fail(
          show"/etc/nixos/$file is missing on $target. Generate it there first, e.g.: sudo nixos-generate-config"
        ).now
    }

  /** Root on the target, to activate, or to read Plasma panel layouts that aren't
    * exported: asks for the sudo password and checks it (before the build).
    */
  def becomeRoot(using Frame): Root < Proc.Effects =
    direct {
      Proc.outputOr(onTarget("id", "-u"), root)(_ => show"could not run id on $target.").now match
        case "0" => Root.Already
        case _   => askPassword(1).now
    }

  def askPassword(attempt: Int)(using Frame): Root < Proc.Effects =
    val prompt = show"[sudo] password for $target: "
    direct {
      val password = Sync
        .defer(
          Option(java.lang.System.console()) match
            case Some(console) => Option(console.readPassword(prompt)).map(String(_)).getOrElse("")
            case None =>
              java.lang.System.err.print(prompt)
              Option(scala.io.StdIn.readLine()).getOrElse("")
        )
        .now
      // -k: check the password even if sudo remembers an earlier one.
      val (code, out) = Proc
        .output(
          onTarget("sudo", "--stdin", "--prompt=", "-k", "true"),
          root,
          stdin = Present(password + "\n"),
          mergeErr = true
        )
        .now
      if code == 0 then Root.Sudo(password)
      else if out.contains("incorrect password") then
        Console.printLineErr("Wrong password.").now
        if attempt < 3 then askPassword(attempt + 1).now
        else fail(show"3 wrong sudo passwords for $target. Nothing was changed.").now
      else if out.contains("not in the sudoers") || out.contains("not allowed") then
        fail(show"${target.takeWhile(_ != '@')} may not use sudo on $host (needs to be in the wheel group).").now
      else fail(show"checking the sudo password on $target failed: $out").now
    }

  /** A command run as root on the target (through sudo, unless we're root there), and
    * its input.
    */
  def asRoot(rootAccess: Root, command: String*): (Command, Maybe[String]) =
    rootAccess match
      case Root.Already => (onTarget(command.map(shellQuote).mkString(" ")), Present(""))
      case Root.Sudo(password) =>
        (
          onTarget((Seq("sudo", "--stdin", "--prompt=") ++ command).map(shellQuote).mkString(" ")),
          Present(password + "\n")
        )

  /** Each user's Plasma panel layout: the machine's copy wins, so changes made there
    * end up here. Read from [[plasmaExport]]/<user>/; for a user without that
    * directory (the host doesn't export them yet) from the home as root, since homes
    * aren't readable by others. A user without the files there (never logged in)
    * keeps what's here. Only for hosts with a plasma/ directory (and
    * desktop.plasmaPanels set).
    */
  def copyPlasmaFiles(rootAccess: Root < Proc.Effects)(using Frame): Unit < Script =
    direct {
      // Normal users: NixOS gives them uids from 1000.
      val users = Proc
        .outputOr(
          onTarget("""awk -F: '$3 >= 1000 && $3 < 30000 { print $1 ":" $6 }' /etc/passwd"""),
          root
        )(_ => show"could not read the users on $target.")
        .now
      Kyo.foreachDiscard(users.linesIterator.filter(_.nonEmpty).toVector)(copyPlasmaFilesOf(rootAccess, _)).now
    }

  /** [[copyPlasmaFiles]] for one `user:home` line of /etc/passwd. */
  def copyPlasmaFilesOf(rootAccess: Root < Proc.Effects, line: String)(using Frame): Unit < Script =
    val (user, home) = line.span(_ != ':')
    val dir = hostDir / "plasma" / user
    val exported = show"$plasmaExport/$user"
    direct {
      dir.mkDir.now
      val canRead = Proc
        .status(onTarget(Seq("test", "-r", exported, "-a", "-x", exported).map(shellQuote).mkString(" ")), root)
        .now == 0
      // A file's command and its input.
      val read: String => (Command, Maybe[String]) =
        if canRead then
          file => (onTarget(Seq("cat", show"$exported/$file").map(shellQuote).mkString(" ")), Present(""))
        else
          val access = rootAccess.now
          file => asRoot(access, "cat", show"${home.drop(1)}/.config/$file")
      Kyo
        .foreachDiscard(plasmaFiles) { file =>
          val (command, stdin) = read(file)
          into(dir / file, command, stdin, Out.Discard).map(when(_)(noteIfChanged(dir / file)))
        }
        .now
      if dir.list.now.isEmpty then dir.remove.unit.now
    }

  /** Builds the system, by default on the target (mostly: downloads from
    * cache.nixos.org). Pushing a locally built one requires the target to trust our
    * ssh user (nix.settings.trusted-users), which a freshly installed machine doesn't
    * yet.
    */
  def build(drv: String)(using Frame): String < Proc.Effects =
    direct {
      val buildOn = args.buildOn match
        case BuildOn.Target => BuildOn.Target
        case BuildOn.Local =>
          val (code, json) = Proc
            .output(Nix("store", "info", "--json", "--store", show"ssh-ng://$target"), root, env = nixEnv)
            .now
          val trusted = code == 0 && Json.decode[StoreInfo](json).toMaybe.exists(_.trusted.contains(true))
          if trusted then BuildOn.Local
          else
            Console.printLineErr(show"note: $target doesn't trust this user yet, building there instead.").now
            BuildOn.Target
      buildOn match
        case BuildOn.Local =>
          Console.printLineErr("Building locally...").now
          val system = Proc
            .outputOr(Nix("build", "--no-link", "--print-out-paths", show"$drv^*"), root)(_ =>
              show"the build failed (see above). $nothingChanged"
            )
            .now
          Console.printLineErr(show"Copying to $target...").now
          // --substitute-on-destination: the target fetches what it can from
          // cache.nixos.org instead of receiving it from us.
          Proc
            .succeedOr(
              Nix("copy", "--substitute-on-destination", "--to", show"ssh-ng://$target", system),
              root,
              nixEnv
            )(_ => show"copying the system to $target failed (see above). $nothingChanged")
            .now
          system
        case BuildOn.Target =>
          Console.printLineErr(show"Building on $target...").now
          Proc
            .succeedOr(Nix("copy", "--derivation", "--to", show"ssh://$target", drv), root, nixEnv)(_ =>
              show"copying the build instructions to $target failed (see above). $nothingChanged"
            )
            .now
          Proc
            .outputOr(
              onTarget(
                Nix("build", "--no-link", "--print-out-paths", show"$drv^*").args.map(shellQuote).mkString(" ")
              ),
              root
            )(_ => show"the build on $target failed (see above). $nothingChanged")
            .now
    }

  /** As root on the target, what nixos-rebuild does: record the new generation (for
    * switch/boot), then run switch-to-configuration through systemd-run, so it
    * finishes even if the ssh connection drops halfway. Exit codes: 10 = recording
    * the generation failed, otherwise switch-to-configuration's (4 = it switched, but
    * some units failed).
    */
  val activateScript: String =
    """
      |  action=$1 system=$2
      |  if [ "$action" = switch ] || [ "$action" = boot ]; then
      |    /run/current-system/sw/bin/nix-env -p /nix/var/nix/profiles/system --set "$system" || exit 10
      |  fi
      |  export NIXOS_INSTALL_BOOTLOADER=0
      |  exec /run/current-system/sw/bin/systemd-run -E LOCALE_ARCHIVE \
      |    -E NIXOS_INSTALL_BOOTLOADER -E NIXOS_NO_CHECK --collect --no-ask-password --pipe \
      |    --quiet --service-type=exec --unit=nixos-rebuild-switch-to-configuration \
      |    "$system/bin/switch-to-configuration" "$action" </dev/null
      |""".stripMargin

  def activate(rootAccess: Root, action: Action, system: String)(using Frame): Unit < Proc.Effects =
    val (command, stdin) = asRoot(rootAccess, "sh", "-c", activateScript, "sh", action.name, system)
    direct {
      Console.printLineErr(show"Activating (${action.name}) on $target...").now
      Proc.status(command, root, stdin = stdin).now match
        case 0 =>
          Console.printLine(show"Done: $host is on $system (${action.name}).").now
          setUpRouter(action).now
        case 10 => fail(show"recording the new generation on $host failed (see above). Nothing was changed.").now
        case 4 =>
          fail(
            show"$host switched to the new configuration, but some services failed (see above). " +
              show"Check them with: ssh $target systemctl --failed"
          ).now
        case 255 =>
          fail(
            show"lost the connection to $target while activating. The switch may still have " +
              show"finished there; check with: ssh $target readlink /run/current-system " +
              show"(the new system is $system)"
          ).now
        case _ if action == Action.Switch || action == Action.Boot =>
          fail(
            show"activating the new configuration on $host failed (see above). It's recorded as " +
              show"the newest generation, so $host boots into it and may be partly running it. " +
              show"To go back: ssh -t $target sudo nixos-rebuild switch --rollback"
          ).now
        case _ =>
          fail(show"${action.name} on $host failed (see above). A reboot returns it to its current generation.").now
    }

/** The system's derivation and the store path it builds. */
final case class Toplevel(drv: String, out: String) derives Schema

/** What `nix store info --json` says (only what's used). */
final case class StoreInfo(trusted: Maybe[Boolean]) derives Schema
