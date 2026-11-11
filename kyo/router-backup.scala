#!/usr/bin/env bash
//usr/bin/env true; exec "$(dirname "$0")/scala-lib/run" "$0" "$@" # bash runs this line; Scala sees a comment
// Backs up the router's configuration into a git repository: RouterOS's `/export`
// (readable, diffable; without passwords and keys, which RouterOS leaves out) and, next
// to it, a binary backup (`/system backup save`, encrypted with the password in
// secrets/router.yaml), which has those too and restores the router exactly. Commits and
// pushes only when the export changed (the binary backup differs every time, so it's
// taken only then). The password comes from your keyring (Secret Service, e.g.
// KWallet); if it isn't there, from sops (a touch of your FIDO2 key), and is
// stored in the keyring for next time.
// router.scala runs it before and after setting the router up, so changes made by hand
// get their own commit too.
// The repository and files are the host's `router.backup`, the router its
// `router.ssh` (override with ROUTER=user@host). The clone is kept in
// ${XDG_STATE_HOME:-~/.local/state}/nixos-conf/router-backup; a commit that couldn't be
// pushed is pushed by the next run.
// Usage: --help.
//> using file scala-lib

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kyo.*
import nixosconf.lib.{*, given}

/** The host's `router.backup` and `router.ssh`. */
final case class Backup(repository: String, file: String, binaryFile: String, ssh: String) derives Schema

/** router-backup.scala's options: none but --help. */
final case class Options()

object Main extends ScriptApp[Options]:
  override def name: String = "router-backup.scala"
  def description: String =
    """Commits the router's configuration (/export and an encrypted binary backup) to the
      |host's router.backup.repository, when it changed.
      |
      |Arguments: <name> [commit message]
      |Environment variables:
      |  ROUTER=user@host  The router's ssh destination (default: the host's router.ssh).""".stripMargin

  def program(options: Options, args: Chunk[String]): Int < (Script & Scope) =
    direct {
      val root = Repo.root.now
      val known = KnownHosts.discover(root).now
      val (host, message) = args.toList match
        case List(name)          => (known.parse(name).now, Absent)
        case List(name, message) => (known.parse(name).now, Present(message))
        case _                   => usage("expected <name> [commit message]").now
      val routerOverride = env("ROUTER").now
      // null for hosts without the router module or without backups.
      val backup = Nix
        .evalJson[Maybe[Backup]](
          host.config(FlakeRef.of(root)),
          """c: if c ? router && c.router.backup.repository != null then
            |  { inherit (c.router.backup) repository file binaryFile; inherit (c.router) ssh; }
            |else null""".stripMargin,
          root
        )(show"the configuration of ${host.name} doesn't evaluate (see above).")
        .now
      backup.toOption match
        case None =>
          Console.printLine(show"${host.name} has no router backups (router.backup.repository isn't set).").now
        case Some(backup) =>
          RouterBackup(
            root,
            backup,
            routerOverride.getOrElse(backup.ssh),
            message.getOrElse(show"./router-backup.scala ${host.name}")
          ).run.now
      0
    }

/** Sets `path`'s permissions, e.g. `rwx------`. */
def setPerms(path: Path, perms: String)(using Frame): Unit < Sync =
  Sync.defer(Files.setPosixFilePermissions(path.toJava, PosixFilePermissions.fromString(perms))).unit

final case class RouterBackup(root: Path, backup: Backup, router: String, message: String):
  val secrets: Path = root / "secrets" / "router.yaml"
  val repo: String = backup.repository

  def clonePath(using Frame): Path < Sync =
    direct {
      val state = env("XDG_STATE_HOME").now match
        case Present(dir) => Path(dir)
        case Absent       => Path(System.property[String]("user.home").now.getOrElse("/")) / ".local" / "state"
      state / "nixos-conf" / "router-backup"
    }

  def warn(message: String)(using Frame): Unit < Sync = Console.printLineErr(show"warning: $message")

  def run(using Frame): Unit < Script =
    direct {
      val clone = clonePath.now
      val git = Git(clone)
      if !(clone / ".git").isDirectory.now then
        // fold, not foreach: Maybe.foreach takes A => Unit, which would drop mkDir's effect.
        clone.parent.fold[Unit < PathWrite](())(_.mkDir).now
        Proc
          .succeedOr(Command("git", "clone", "--quiet", repo, clone.toString), root)(_ =>
            show"could not clone $repo."
          )
          .now
      // What the bash version's umask 077 did: only you can read the clone.
      setPerms(clone, "rwx------").now
      val origin = git.output("remote", "get-url", "origin").now
      if origin != repo then fail(show"$clone is a clone of $origin, not $repo; move it away.").now
      val branch = git.output("symbolic-ref", "--short", "HEAD").now
      update(git, branch).now
      Console.printLineErr(show"Backing up $router's configuration...").now
      val config = Proc
        .outputOr(Command("ssh", router, "/export"), root)(_ => show"could not export $router's configuration.")
        .now
      if !config.contains("by RouterOS") then fail(show"$router's export looks wrong: $config").now
      commit(clone, git, config).now
      push(git, branch).now
    }

  /** Up to date with the repository (keeping commits a failed push left behind);
    * offline, it carries on and the push fails.
    */
  def update(git: Git, branch: String)(using Frame): Unit < Proc.Effects =
    direct {
      if git.status("fetch", "--quiet", "origin").now != 0 then
        warn(show"could not fetch $repo; going on with the clone as it is.").now
      else if git.status("rebase", "--quiet", show"origin/$branch").now != 0 then
        val _ = git.status("rebase", "--abort").now
        fail(show"${git.dir} doesn't rebase onto origin/$branch; sort it out there.").now
    }

  def commit(clone: Path, git: Git, config: String)(using Frame): Unit < Script =
    val exportFile = clone / backup.file
    val binaryFile = clone / backup.binaryFile
    val exported = config + "\n"
    // The first line has the time of the export, which alone isn't a change.
    def body(s: String) = s.linesWithSeparators.drop(1).mkString
    direct {
      val unchanged =
        exportFile.isRegularFile.now && binaryFile.isRegularFile.now && body(exportFile.read.now) == body(exported)
      if unchanged then Console.printLine("The router's configuration hasn't changed since the last backup.").now
      else
        val password = this.password.now
        binaryBackup(password, binaryFile).now
        exportFile.write(exported).now
        setPerms(exportFile, "rw-------").now
        git.succeed("add", "--", backup.file, backup.binaryFile).now
        git.succeed("commit", "--quiet", "-m", message, "--", backup.file, backup.binaryFile).now
        Console.printLine(show"Committed: $message").now
    }

  /** From your keyring (KWallet, through Secret Service); the first time, from sops
    * (the flake's, with the FIDO2 plugin: a touch; see docs/secrets.md), and kept in
    * the keyring.
    */
  def password(using Frame): String < Proc.Effects =
    def secretTool(args: String*) = Nix(Seq("shell", "nixpkgs#libsecret", "-c", "secret-tool") ++ args*)
    val keyring = Seq("service", "nixos-conf", "key", "router-backup-password")
    direct {
      val (code, stored) = Proc.output(secretTool("lookup" +: keyring*), root).now
      if code == 0 && stored.nonEmpty then stored
      else
        Console.printLineErr(show"The backup password isn't in your keyring; decrypting it from $secrets...").now
        val password = Proc
          .outputOr(
            Nix(
              "run",
              show"${FlakeRef.of(root).unwrap}#sops",
              "--",
              "--decrypt",
              "--extract",
              """["backup-password"]""",
              secrets.toString
            ),
            root
          )(_ => show"could not decrypt the backup password from $secrets.")
          .now
        // Not even starting it only costs the keyring.
        val kept = Abort
          .run[Stop](
            // secret-tool reads the secret from stdin (as it is: no newline added).
            Proc.status(
              secretTool(("store" +: "--label=nixos-conf: router backup password" +: keyring)*),
              root,
              stdin = Present(password)
            )
          )
          .now
          .getOrElse(1)
        if kept != 0 then warn("could not store the backup password in your keyring.").now
        password
    }

  def binaryBackup(password: String, binaryFile: Path)(using Frame): Unit < (Proc.Effects & PathWrite) =
    // In a RouterOS string, \ " and $ need a backslash.
    val escaped = password.replaceAll("""[\\"$]""", """\\$0""")
    val name = "nixos-conf-backup"
    val newFile = Path(binaryFile.toString + ".new")
    direct {
      // RouterOS's ssh exits with 0 even when the command fails, so it's judged by
      // its output.
      val out = Proc
        .outputOr(
          Command("ssh", router, show"""/system backup save name=$name dont-encrypt=no password="$escaped""""),
          root,
          mergeErr = true
        )(out => show"could not make the binary backup on $router: $out")
        .now
      if !out.contains("Configuration backup saved") then fail(show"the binary backup failed on $router: $out").now
      binaryFile.parent.fold[Unit < PathWrite](())(_.mkDir).now
      Proc
        .succeedOr(Command("scp", "-q", show"$router:$name.backup", newFile.toString), root)(_ =>
          show"could not copy the binary backup from $router."
        )
        .now
      setPerms(newFile, "rw-------").now
      val removed = Proc.status(Command("ssh", router, show"/file remove $name.backup"), root).now
      if removed != 0 then warn(show"could not remove $name.backup on $router.").now
      newFile.move(binaryFile, Path.MoveOptions(replace = Path.Replace.Existing)).now
    }

  def push(git: Git, branch: String)(using Frame): Unit < Proc.Effects =
    direct {
      val (code, log) = Proc.output(git.cmd("log", show"origin/$branch..HEAD"), git.dir).now
      if code == 0 && log.nonEmpty then
        if git.status("push", "--quiet", "origin", branch).now == 0 then Console.printLine(show"Pushed to $repo.").now
        else
          fail(
            show"could not push to $repo; the backup is committed in ${git.dir} and the " +
              "next run pushes it."
          ).now
    }

/** git in the clone. */
final case class Git(dir: Path):
  def cmd(args: String*): Command = Command(Seq("git", "-C", dir.toString) ++ args*)
  def status(args: String*)(using Frame): Int < Proc.Effects = Proc.status(cmd(args*), dir)
  def succeed(args: String*)(using Frame): Unit < Proc.Effects = Proc.succeed(cmd(args*), dir)
  def output(args: String*)(using Frame): String < Proc.Effects =
    Proc.outputOr(cmd(args*), dir)(_ => show"`git ${args.mkString(" ")}` in $dir failed")
