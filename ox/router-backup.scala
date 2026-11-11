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

import cats.syntax.all.*
import com.monovore.decline.{Command, Opts}
import nixosconf.lib.*
import nixosconf.lib.given
import ox.{ExitCode, Ox, either}
import ox.either.ok
import upickle.default.ReadWriter

final case class Args(host: HostName, message: Option[String], router: Option[String])

object Args:
  def command(known: KnownHosts): Command[Args] =
    import known.given
    Command(
      "router-backup.scala",
      "Commits the router's configuration (/export and an encrypted binary backup) to " +
        "the host's router.backup.repository, when it changed."
    )(
      (
        Opts.argument[HostName]("name"),
        Opts.argument[String]("commit message").orNone,
        Opts.env[String]("ROUTER", "The router's ssh destination (default: the host's router.ssh).").orNone
      ).mapN(Args.apply)
    )

/** The host's `router.backup` and `router.ssh`. */
final case class Backup(repository: String, file: String, binaryFile: String, ssh: String)
    derives ReadWriter

object Main extends ScriptApp:
  def program(args: List[String])(using Ox): Either[ScriptError, ExitCode] = either {
    val root = Repo.root.ok()
    val known = KnownHosts.discover(root).ok()
    val parsed = Cli.parse(Args.command(known), args).ok()
    // null for hosts without the router module or without backups.
    val backup = Nix
      .evalJson[Option[Backup]](
        parsed.host.config(FlakeRef.of(root)),
        """c: if c ? router && c.router.backup.repository != null then
          |  { inherit (c.router.backup) repository file binaryFile; inherit (c.router) ssh; }
          |else null""".stripMargin,
        root
      )(show"the configuration of ${parsed.host.name} doesn't evaluate (see above).")
      .ok()
    backup match
      case None => println(show"${parsed.host.name} has no router backups (router.backup.repository isn't set).")
      case Some(backup) =>
        RouterBackup(
          root,
          backup,
          parsed.router.getOrElse(backup.ssh),
          parsed.message.getOrElse(show"./router-backup.scala ${parsed.host.name}")
        ).run.ok()
    ExitCode.Success
  }

final case class RouterBackup(root: os.Path, backup: Backup, router: String, message: String):
  val secrets: os.Path = root / "secrets" / "router.yaml"
  val repo: String = backup.repository

  val clonePath: os.Path =
    sys.env
      .get("XDG_STATE_HOME")
      .map(os.Path(_))
      .getOrElse(os.home / ".local" / "state") / "nixos-conf" / "router-backup"

  def warn(message: String): Unit = Console.err.println(show"warning: $message")

  def run: Either[ScriptError, Unit] = either {
    val clone = clonePath
    val git = Git(clone)
    if !os.isDir(clone / ".git") then
      os.makeDir.all(clone / os.up)
      Proc
        .succeedOr(os.proc("git", "clone", "--quiet", repo, clone), root)(_ => show"could not clone $repo.")
        .ok()
    // What the bash version's umask 077 did: only you can read the clone.
    os.perms.set(clone, "rwx------")
    val origin = git.output("remote", "get-url", "origin").ok()
    failUnless(origin == repo)(show"$clone is a clone of $origin, not $repo; move it away.").ok()
    val branch = git.output("symbolic-ref", "--short", "HEAD").ok()
    update(git, branch).ok()
    Console.err.println(show"Backing up $router's configuration...")
    val config = Proc
      .outputOr(os.proc("ssh", router, "/export"), root)(_ => show"could not export $router's configuration.")
      .ok()
    failUnless(config.contains("by RouterOS"))(show"$router's export looks wrong: $config").ok()
    commit(clone, git, config).ok()
    push(git, branch).ok()
  }

  /** Up to date with the repository (keeping commits a failed push left behind);
    * offline, it carries on and the push fails.
    */
  def update(git: Git, branch: String): Either[ScriptError, Unit] =
    if git.status("fetch", "--quiet", "origin") == 0 then
      if git.status("rebase", "--quiet", show"origin/$branch") == 0 then Right(())
      else
        git.status("rebase", "--abort")
        fail(show"${git.dir} doesn't rebase onto origin/$branch; sort it out there.")
    else Right(warn(show"could not fetch $repo; going on with the clone as it is."))

  def commit(clone: os.Path, git: Git, config: String): Either[ScriptError, Unit] = either {
    val exportFile = clone / os.RelPath(backup.file)
    val binaryFile = clone / os.RelPath(backup.binaryFile)
    val exported = config + "\n"
    // The first line has the time of the export, which alone isn't a change.
    def body(s: String) = s.linesWithSeparators.drop(1).mkString
    Seq(exportFile, binaryFile).foreach(f => os.makeDir.all(f / os.up))
    if os.isFile(exportFile) && os.isFile(binaryFile) && body(os.read(exportFile)) == body(exported) then
      println("The router's configuration hasn't changed since the last backup.")
    else
      binaryBackup(password.ok(), binaryFile).ok()
      os.write.over(exportFile, exported, perms = "rw-------")
      git.succeed("add", "--", backup.file, backup.binaryFile).ok()
      git.succeed("commit", "--quiet", "-m", message, "--", backup.file, backup.binaryFile).ok()
      println(show"Committed: $message")
  }

  /** From your keyring (KWallet, through Secret Service); the first time, from sops
    * (the flake's, with the FIDO2 plugin: a touch; see docs/secrets.md), and kept in
    * the keyring.
    */
  def password: Either[ScriptError, String] = either {
    def secretTool(args: String*) =
      Nix("shell", "nixpkgs#libsecret", "-c", "secret-tool", args)
    val keyring = Seq("service", "nixos-conf", "key", "router-backup-password")
    Proc.output(secretTool("lookup" +: keyring*), root) match
      case (0, password) if password.nonEmpty => password
      case _ =>
        Console.err.println(show"The backup password isn't in your keyring; decrypting it from $secrets...")
        val password = Proc
          .outputOr(
            Nix(
              "run",
              show"${FlakeRef.of(root).unwrap}#sops",
              "--",
              "--decrypt",
              "--extract",
              """["backup-password"]""",
              secrets
            ),
            root
          )(_ => show"could not decrypt the backup password from $secrets.")
          .ok()
        val stored =
          // secret-tool reads the secret from stdin (as it is: no newline added). Only
          // its not starting is caught (never every exception: .ok() breaks out with one).
          try
            Proc.status(
              secretTool(("store" +: "--label=nixos-conf: router backup password" +: keyring)*),
              root,
              stdin = password
            )
          catch case _: java.io.IOException => 1
        if stored != 0 then warn("could not store the backup password in your keyring.")
        password
  }

  def binaryBackup(password: String, binaryFile: os.Path): Either[ScriptError, Unit] = either {
    // In a RouterOS string, \ " and $ need a backslash.
    val escaped = password.replaceAll("""[\\"$]""", """\\$0""")
    val name = "nixos-conf-backup"
    val newFile = binaryFile / os.up / show"${binaryFile.last}.new"
    // RouterOS's ssh exits with 0 even when the command fails, so it's judged by
    // its output.
    val out = Proc
      .outputOr(
        os.proc("ssh", router, show"""/system backup save name=$name dont-encrypt=no password="$escaped""""),
        root,
        mergeErr = true
      )(out => show"could not make the binary backup on $router: $out")
      .ok()
    failUnless(out.contains("Configuration backup saved"))(show"the binary backup failed on $router: $out").ok()
    Proc
      .succeedOr(os.proc("scp", "-q", show"$router:$name.backup", newFile), root)(_ =>
        show"could not copy the binary backup from $router."
      )
      .ok()
    os.perms.set(newFile, "rw-------")
    if Proc.status(os.proc("ssh", router, show"/file remove $name.backup"), root) != 0 then
      warn(show"could not remove $name.backup on $router.")
    os.move.over(newFile, binaryFile)
  }

  def push(git: Git, branch: String): Either[ScriptError, Unit] =
    Proc.output(os.proc("git", "-C", git.dir, "log", show"origin/$branch..HEAD"), git.dir) match
      case (0, log) if log.nonEmpty =>
        if git.status("push", "--quiet", "origin", branch) == 0 then Right(println(show"Pushed to $repo."))
        else
          fail(
            show"could not push to $repo; the backup is committed in ${git.dir} and the " +
              "next run pushes it."
          )
      case _ => Right(())

/** git in the clone. */
final case class Git(dir: os.Path):
  def cmd(args: String*): os.proc = os.proc("git", "-C", dir, args)
  def status(args: String*): Int = Proc.status(cmd(args*), dir)
  def succeed(args: String*): Either[ScriptError, Unit] = Proc.succeed(cmd(args*), dir)
  def output(args: String*): Either[ScriptError, String] =
    Proc.outputOr(cmd(args*), dir)(_ => show"`git ${args.mkString(" ")}` in $dir failed")
