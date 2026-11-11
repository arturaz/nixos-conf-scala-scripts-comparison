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

import cats.syntax.all.*
import com.monovore.decline.Command
import nixosconf.lib.*
import nixosconf.lib.given
import zio.{ExitCode, IO, Ref, Scope, UIO, ZIO}
import zio.interop.catz.*

object Main extends ScriptApp:
  def command(known: KnownHosts): Command[DeployArgs] =
    Command(
      "deploy.scala",
      "Evaluates the host's configuration here, builds it on the machine (or here, " +
        "BUILD_LOCAL=1) and activates it there; after switch or test, sets the router up."
    )(DeployArgs.opts(known))

  def program(args: List[String]): IO[ScriptError, ExitCode] =
    for
      root <- Repo.root
      known <- KnownHosts.discover(root)
      args <- Cli.parse(command(known), args)
      _ <- ZIO.scoped(Deploy.session(root, args).flatMap(_.run))
    yield ExitCode.success

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
  def session(root: os.Path, args: DeployArgs): ZIO[Scope, Nothing, Deploy] =
    val target = args.destination
    for
      tmp <- ZIO.acquireRelease(blocking(os.temp.dir()))(dir => blocking(os.remove.all(dir)))
      sshOpts = Seq(
        "-o",
        "ControlMaster=auto",
        "-o",
        show"ControlPath=$tmp/ssh-%C",
        "-o",
        "ControlPersist=60"
      )
      _ <- ZIO.addFinalizer(
        Proc.status(os.proc("ssh", sshOpts, "-O", "exit", target), root, Proc.devNull, Proc.devNull)
      )
      uncommitted <- ZIO.acquireRelease(Ref.make(Vector.empty[String]))(
        _.get.flatMap(files =>
          (printOut(show"\nUpdated from $target, needs committing:") *>
            files.traverse_(f => printOut(show"  $f"))).whenA(files.nonEmpty)
        )
      )
    yield Deploy(root, args, target, sshOpts, uncommitted)

final case class Deploy(
    root: os.Path,
    args: DeployArgs,
    target: String,
    sshOpts: Seq[String],
    uncommitted: Ref[Vector[String]]
):
  val host: String = args.host.name
  val hostDir: os.Path = root / "hosts" / host
  val flake: FlakeRef = FlakeRef.of(root)
  /** For nix's own ssh. */
  val nixEnv: Map[String, String] = Map("NIX_SSHOPTS" -> sshOpts.mkString(" "))
  val nothingChanged = show"Nothing was changed on $host."

  def onTarget(command: String*): os.proc = os.proc("ssh", sshOpts, target, command)

  /** The error is printed here, before the session's end lists the files updated
    * from the machine.
    */
  def run: IO[ScriptError, Unit] =
    steps.catchSome { case Fail(message, code) => printErr(show"error: $message") *> ZIO.fail(Exit(code)) }

  def steps: IO[ScriptError, Unit] =
    for
      // ssh exits with 255 on connection/auth errors.
      connected <- Proc.status(onTarget("true"), root)
      _ <- failUnless(connected == 0)(
        show"could not connect to $target. Is it on and on the network? Try: ssh $target"
      )
      _ <- machineFiles.traverse_(copyMachineFile(_))
      // Asked for (and checked) when first needed, then kept.
      rootAccess <- becomeRoot.memoize
      hasPlasma <- blocking(os.isDir(hostDir / "plasma"))
      _ <- copyPlasmaFiles(rootAccess).whenA(hasPlasma)
      _ <- (Proc
        .succeedOr(Nix("flake", "update", "--flake", flake.unwrap), root)(_ =>
          show"updating flake.lock failed (see above). $nothingChanged"
        )
         *> printOut("Updated flake.lock; commit it.")).whenA(args.update)
      // The repo is public: refuse to deploy (and so, likely, to push next) a plaintext
      // secret. After the machine's files above are copied in, as they get committed
      // too.
      _ <- Proc
        .succeedOr(os.proc(root / "check-secrets.scala"), root)(_ =>
          show"a secret would be published (see above). $nothingChanged"
        )
      _ <- printErr("Evaluating the configuration...")
      toplevel <- Nix.evalJson[Toplevel](
        Installable(flake, Seq("system", "build", "toplevel").foldLeft(args.host.config(flake).attribute)(
          (path, name) => path / AttrName.make.orThrow(name)
        )),
        "t: { drv = t.drvPath; out = t.outPath; }",
        root
      )(show"the configuration doesn't evaluate (see above). $nothingChanged")
      _ <- args.action match
        case Action.Build =>
          build(toplevel.drv).flatMap(system =>
            printOut(show"Done: $system is built and on $target.")
          )
        case action =>
          // Widened: cats' F is invariant, so on a UIO the branches couldn't fail.
          (alreadyThere(action, toplevel.out): IO[ScriptError, Boolean]).ifM(
            printOut(show"Nothing to do: $host already ${action.already} ${toplevel.out}.") *>
              setUpRouter(action),
            // The password before the build, so a typo doesn't cost one.
            for
              access <- rootAccess
              system <- build(toplevel.drv)
              _ <- activate(access, action, system)
            yield ()
          )
    yield ()

  /** Whether the target already is where `action` would take it: running `system`
    * (switch, test, dry-activate) and booting into it (switch, boot). Then there's
    * nothing to activate, and no need for root.
    */
  def alreadyThere(action: Action, system: String): UIO[Boolean] =
    Proc
      .output(onTarget("readlink -f /run/current-system; readlink -f /nix/var/nix/profiles/system"), root)
      .map {
        case (0, out) =>
          out.linesIterator.toList match
            case List(current, booted) =>
              val runs = current == system
              val boots = booted == system
              action match
                case Action.Switch                   => runs && boots
                case Action.Test | Action.DryActivate => runs
                case Action.Boot                     => boots
                case Action.Build                    => false
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
  def setUpRouter(action: Action): IO[ScriptError, Unit] =
    Proc
      .succeedOr(os.proc(root / "router.scala", host), root)(_ =>
        show"$host is switched, but setting up the router failed (see above); retry with: ./router.scala $host"
      )
      .whenA(action == Action.Switch || action == Action.Test)

  /** Lists `file` for committing if it's new or different from the last commit. */
  def noteIfChanged(file: os.Path): IO[ScriptError, Unit] =
    val rel = file.relativeTo(root).toString
    Vcs
      .changed(root, Seq(rel))
      .flatMap(changed => uncommitted.update(_ :+ rel).whenA(changed.nonEmpty))

  /** Runs `proc` with its output into `dest` (through a temporary file, so a failure
    * leaves `dest` as it was); whether it succeeded.
    */
  def into(
      dest: os.Path,
      proc: os.proc,
      stdin: os.ProcessInput = os.Inherit,
      stderr: os.ProcessOutput = os.Inherit
  ): UIO[Boolean] =
    val tmp = dest / os.up / show"${dest.last}.tmp"
    Proc.status(proc, root, tmp, stderr, stdin = stdin).flatMap {
      case 0 => blocking(os.move.over(tmp, dest)).as(true)
      case _ => blocking(os.remove(tmp)).as(false)
    }

  def copyMachineFile(file: String): IO[ScriptError, Unit] =
    val dest = hostDir / file
    into(dest, onTarget(show"cat /etc/nixos/$file")).flatMap {
      case true => noteIfChanged(dest)
      case false =>
        fail(show"/etc/nixos/$file is missing on $target. Generate it there first, e.g.: sudo nixos-generate-config")
    }

  /** Root on the target, to activate, or to read Plasma panel layouts that aren't
    * exported: asks for the sudo password and checks it (before the build).
    */
  def becomeRoot: IO[ScriptError, Root] =
    Proc
      .outputOr(onTarget("id", "-u"), root)(_ => show"could not run id on $target.")
      .flatMap {
        case "0" => ZIO.succeed(Root.Already)
        case _   => askPassword(1)
      }

  def askPassword(attempt: Int): IO[ScriptError, Root] =
    val prompt = show"[sudo] password for $target: "
    for
      password <- blocking(
        Option(System.console()) match
          case Some(console) => Option(console.readPassword(prompt)).map(String(_)).getOrElse("")
          case None =>
            System.err.print(prompt)
            Option(scala.io.StdIn.readLine()).getOrElse("")
      )
      // -k: check the password even if sudo remembers an earlier one.
      checked <- Proc.output(
        onTarget("sudo", "--stdin", "--prompt=", "-k", "true"),
        root,
        stdin = password + "\n",
        mergeErr = true
      )
      result <- checked match
        case (0, _) => ZIO.succeed(Root.Sudo(password))
        case (_, out) if out.contains("incorrect password") =>
          if attempt < 3 then printErr("Wrong password.") *> askPassword(attempt + 1)
          else printErr("Wrong password.") *>
            fail(show"3 wrong sudo passwords for $target. Nothing was changed.")
        case (_, out) if out.contains("not in the sudoers") || out.contains("not allowed") =>
          fail(show"${target.takeWhile(_ != '@')} may not use sudo on $host (needs to be in the wheel group).")
        case (_, out) => fail(show"checking the sudo password on $target failed: $out")
    yield result

  /** A command run as root on the target (through sudo, unless we're root there), and
    * its input.
    */
  def asRoot(rootAccess: Root, command: String*): (os.proc, os.ProcessInput) =
    rootAccess match
      case Root.Already => (onTarget(command.map(shellQuote).mkString(" ")), "")
      case Root.Sudo(password) =>
        (
          onTarget((Seq("sudo", "--stdin", "--prompt=") ++ command).map(shellQuote).mkString(" ")),
          password + "\n"
        )

  /** Each user's Plasma panel layout: the machine's copy wins, so changes made there
    * end up here. Read from [[plasmaExport]]/<user>/; for a user without that
    * directory (the host doesn't export them yet) from the home as root, since homes
    * aren't readable by others. A user without the files there (never logged in)
    * keeps what's here. Only for hosts with a plasma/ directory (and
    * desktop.plasmaPanels set).
    */
  def copyPlasmaFiles(rootAccess: IO[ScriptError, Root]): IO[ScriptError, Unit] =
    for
      // Normal users: NixOS gives them uids from 1000.
      users <- Proc
        .outputOr(
          onTarget("""awk -F: '$3 >= 1000 && $3 < 30000 { print $1 ":" $6 }' /etc/passwd"""),
          root
        )(_ => show"could not read the users on $target.")
      _ <- users.linesIterator.filter(_.nonEmpty).toVector.traverse_ { line =>
        val (user, home) = line.span(_ != ':')
        val dir = hostDir / "plasma" / user
        val exported = show"$plasmaExport/$user"
        for
          _ <- blocking(os.makeDir.all(dir))
          canRead <- Proc
            .status(onTarget(Seq("test", "-r", exported, "-a", "-x", exported).map(shellQuote).mkString(" ")), root)
            .map(_ == 0)
          // A file's command and its input.
          read <-
            if canRead then
              ((file: String) =>
                (onTarget(Seq("cat", show"$exported/$file").map(shellQuote).mkString(" ")), "": os.ProcessInput)
              ).pure[ScriptIO]
            else
              rootAccess.map(access =>
                (file: String) => asRoot(access, "cat", show"${home.drop(1)}/.config/$file")
              )
          _ <- plasmaFiles.traverse_ { file =>
            val (proc, stdin) = read(file)
            into(dir / file, proc, stdin, Proc.devNull).flatMap(noteIfChanged(dir / file).whenA(_))
          }
          _ <- blocking(if os.list(dir).isEmpty then os.remove(dir))
        yield ()
      }
    yield ()

  /** Builds the system, by default on the target (mostly: downloads from
    * cache.nixos.org). Pushing a locally built one requires the target to trust our
    * ssh user (nix.settings.trusted-users), which a freshly installed machine doesn't
    * yet.
    */
  def build(drv: String): IO[ScriptError, String] =
    val buildOn = args.buildOn match
      case BuildOn.Target => ZIO.succeed(BuildOn.Target)
      case BuildOn.Local =>
        Proc
          .output(Nix("store", "info", "--json", "--store", show"ssh-ng://$target"), root, env = nixEnv)
          .map {
            case (0, json) =>
              scala.util.Try(ujson.read(json).obj.get("trusted").exists(_.boolOpt.contains(true))).getOrElse(false)
            case _ => false
          }
          .flatMap {
            case true => ZIO.succeed(BuildOn.Local)
            case false =>
              printErr(show"note: $target doesn't trust this user yet, building there instead.")
                .as(BuildOn.Target)
          }
    buildOn.flatMap {
      case BuildOn.Local =>
        for
          _ <- printErr("Building locally...")
          system <- Proc
            .outputOr(Nix("build", "--no-link", "--print-out-paths", show"$drv^*"), root)(_ =>
              show"the build failed (see above). $nothingChanged"
            )
          _ <- printErr(show"Copying to $target...")
          // --substitute-on-destination: the target fetches what it can from
          // cache.nixos.org instead of receiving it from us.
          _ <- Proc.succeedOr(
            Nix("copy", "--substitute-on-destination", "--to", show"ssh-ng://$target", system),
            root,
            nixEnv
          )(_ => show"copying the system to $target failed (see above). $nothingChanged")
        yield system
      case BuildOn.Target =>
        for
          _ <- printErr(show"Building on $target...")
          _ <- Proc
            .succeedOr(Nix("copy", "--derivation", "--to", show"ssh://$target", drv), root, nixEnv)(_ =>
              show"copying the build instructions to $target failed (see above). $nothingChanged"
            )
          system <- Proc.outputOr(
            onTarget(
              Nix("build", "--no-link", "--print-out-paths", show"$drv^*").commandChunks.map(shellQuote).mkString(" ")
            ),
            root
          )(_ => show"the build on $target failed (see above). $nothingChanged")
        yield system
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

  def activate(rootAccess: Root, action: Action, system: String): IO[ScriptError, Unit] =
    val (proc, stdin) = asRoot(rootAccess, "sh", "-c", activateScript, "sh", action.name, system)
    printErr(show"Activating (${action.name}) on $target...") *>
      Proc.status(proc, root, stdin = stdin).flatMap {
        case 0 => printOut(show"Done: $host is on $system (${action.name}).") *> setUpRouter(action)
        case 10 => fail(show"recording the new generation on $host failed (see above). Nothing was changed.")
        case 4 =>
          fail(
            show"$host switched to the new configuration, but some services failed (see above). " +
              show"Check them with: ssh $target systemctl --failed"
          )
        case 255 =>
          fail(
            show"lost the connection to $target while activating. The switch may still have " +
              show"finished there; check with: ssh $target readlink /run/current-system " +
              show"(the new system is $system)"
          )
        case _ if action == Action.Switch || action == Action.Boot =>
          fail(
            show"activating the new configuration on $host failed (see above). It's recorded as " +
              show"the newest generation, so $host boots into it and may be partly running it. " +
              show"To go back: ssh -t $target sudo nixos-rebuild switch --rollback"
          )
        case _ => fail(show"${action.name} on $host failed (see above). A reboot returns it to its current generation.")
      }

/** The system's derivation and the store path it builds. */
final case class Toplevel(drv: String, out: String) derives upickle.default.Reader
