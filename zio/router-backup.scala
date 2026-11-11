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

import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import nixosconf.lib.*
import scala.jdk.CollectionConverters.*
import zio.*
import zio.cli.{Args as CliArgs, Command, HelpDoc}
import zio.json.*
import zio.nio.file.{Files, Path}

final case class Args(host: HostName, message: Option[String], router: Option[String])

object Args:
  def parse(known: KnownHosts, args: List[String]): IO[ScriptError, Args] =
    for
      (host, message) <- Cli.parse(
        Command(
          "router-backup.scala",
          known.argument ++
            (CliArgs.text("commit message") ?? "(default: ./router-backup.scala <name>)").atMost(1).map(_.headOption)
        ).withHelp(
          "Commits the router's configuration (/export and an encrypted binary backup) to " +
            "the host's router.backup.repository, when it changed."
        ),
        args,
        HelpDoc.h1("environment variables") + HelpDoc.descriptionList(
          HelpDoc.Span.text("ROUTER") -> HelpDoc.p("The router's ssh destination (default: the host's router.ssh).")
        )
      )
      router <- env("ROUTER")
    yield Args(host, message, router)

/** The host's `router.backup` and `router.ssh`. */
final case class Backup(repository: String, file: String, binaryFile: String, ssh: String) derives JsonDecoder

object Main extends ScriptApp:
  def program(args: List[String]): IO[ScriptError, ExitCode] =
    for
      root <- Repo.root
      known <- KnownHosts.discover(root)
      args <- Args.parse(known, args)
      // null for hosts without the router module or without backups.
      backup <- Nix.evalJson[Option[Backup]](
        args.host.config(FlakeRef.of(root)),
        """c: if c ? router && c.router.backup.repository != null then
          |  { inherit (c.router.backup) repository file binaryFile; inherit (c.router) ssh; }
          |else null""".stripMargin,
        root
      )(show"the configuration of ${args.host.name} doesn't evaluate (see above).")
      _ <- backup match
        case None =>
          printOut(show"${args.host.name} has no router backups (router.backup.repository isn't set).")
        case Some(backup) =>
          RouterBackup(
            root,
            backup,
            args.router.getOrElse(backup.ssh),
            args.message.getOrElse(show"./router-backup.scala ${args.host.name}")
          ).run
    yield ExitCode.success

/** Only the owner may read and write it (and, for a directory, enter it). */
def ownerOnly(path: Path, directory: Boolean): UIO[Unit] =
  Files
    .setPosixFilePermissions(
      path,
      PosixFilePermissions.fromString(if directory then "rwx------" else "rw-------").asScala.toSet
    )
    .orDie

final case class RouterBackup(root: Path, backup: Backup, router: String, message: String):
  val secrets: Path = root / "secrets" / "router.yaml"
  val repo: String = backup.repository

  val clonePath: UIO[Path] =
    env("XDG_STATE_HOME").map(state =>
      state.map(Path(_)).getOrElse(Path(java.lang.System.getProperty("user.home")) / ".local" / "state") /
        "nixos-conf" / "router-backup"
    )

  def warn(message: String): UIO[Unit] = printErr(show"warning: $message")

  def run: IO[ScriptError, Unit] =
    clonePath.flatMap { clone =>
      val git = Git(clone)
      for
        _ <- ZIO.unlessZIO(Files.isDirectory(clone / ".git"))(
          clone.parent.fold(ZIO.unit)(Files.createDirectories(_).orDie) *>
            Proc.succeedOr(Cmd("git", "clone", "--quiet", repo, clone.toString), root)(_ => show"could not clone $repo.")
        )
        // What the bash version's umask 077 did: only you can read the clone.
        _ <- ownerOnly(clone, directory = true)
        origin <- git.output("remote", "get-url", "origin")
        _ <- failUnless(origin == repo)(show"$clone is a clone of $origin, not $repo; move it away.")
        branch <- git.output("symbolic-ref", "--short", "HEAD")
        _ <- update(git, branch)
        _ <- printErr(show"Backing up $router's configuration...")
        config <- Proc.outputOr(Cmd("ssh", router, "/export"), root)(_ => show"could not export $router's configuration.")
        _ <- failUnless(config.contains("by RouterOS"))(show"$router's export looks wrong: $config")
        _ <- commit(clone, git, config)
        _ <- push(git, branch)
      yield ()
    }

  /** Up to date with the repository (keeping commits a failed push left behind);
    * offline, it carries on and the push fails.
    */
  def update(git: Git, branch: String): IO[ScriptError, Unit] =
    git.status("fetch", "--quiet", "origin").flatMap {
      case 0 =>
        git.status("rebase", "--quiet", show"origin/$branch").flatMap {
          case 0 => ZIO.unit
          case _ =>
            git.status("rebase", "--abort") *>
              fail(show"${git.dir} doesn't rebase onto origin/$branch; sort it out there.")
        }
      case _ => warn(show"could not fetch $repo; going on with the clone as it is.")
    }

  def commit(clone: Path, git: Git, config: String): IO[ScriptError, Unit] =
    val exportFile = clone / backup.file
    val binaryFile = clone / backup.binaryFile
    val exported = config + "\n"
    // The first line has the time of the export, which alone isn't a change.
    def body(s: String) = s.linesWithSeparators.drop(1).mkString
    val unchanged =
      (Files.isRegularFile(exportFile) && Files.isRegularFile(binaryFile)) &&
        Files.readAllBytes(exportFile).orDie.map(bytes => body(String(bytes.toArray, "UTF-8")) == body(exported))
    ZIO.foreachDiscard(Seq(exportFile, binaryFile).flatMap(_.parent))(Files.createDirectories(_).orDie) *>
      ZIO.ifZIO(unchanged)(
        printOut("The router's configuration hasn't changed since the last backup."),
        for
          password <- password
          _ <- binaryBackup(password, binaryFile)
          _ <- Files.writeBytes(exportFile, Chunk.fromArray(exported.getBytes("UTF-8"))).orDie
          _ <- ownerOnly(exportFile, directory = false)
          _ <- git.succeed("add", "--", backup.file, backup.binaryFile)
          _ <- git.succeed("commit", "--quiet", "-m", message, "--", backup.file, backup.binaryFile)
          _ <- printOut(show"Committed: $message")
        yield ()
      )

  /** From your keyring (KWallet, through Secret Service); the first time, from sops
    * (the flake's, with the FIDO2 plugin: a touch; see docs/secrets.md), and kept in
    * the keyring.
    */
  val password: IO[ScriptError, String] =
    def secretTool(args: String*) = Nix("shell", "nixpkgs#libsecret", "-c", "secret-tool") ++ args
    val keyring = Seq("service", "nixos-conf", "key", "router-backup-password")
    Proc.output(secretTool("lookup" +: keyring*), root).flatMap {
      case (0, password) if password.nonEmpty => ZIO.succeed(password)
      case _ =>
        for
          _ <- printErr(show"The backup password isn't in your keyring; decrypting it from $secrets...")
          password <- Proc.outputOr(
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
          stored <- Proc
            // secret-tool reads the secret from stdin (as it is: no newline added).
            .status(
              secretTool(("store" +: "--label=nixos-conf: router backup password" +: keyring)*),
              root,
              stdin = Some(password)
            )
            .catchAll(_ => ZIO.succeed(1))
          _ <- warn("could not store the backup password in your keyring.").when(stored != 0)
        yield password
    }

  def binaryBackup(password: String, binaryFile: Path): IO[ScriptError, Unit] =
    // In a RouterOS string, \ " and $ need a backslash.
    val escaped = password.replaceAll("""[\\"$]""", """\\$0""")
    val name = "nixos-conf-backup"
    val newFile = binaryFile.resolveSibling(Path(show"${binaryFile.filename.toString}.new"))
    for
      // RouterOS's ssh exits with 0 even when the command fails, so it's judged by
      // its output.
      out <- Proc.outputOr(
        Cmd("ssh", router, show"""/system backup save name=$name dont-encrypt=no password="$escaped""""),
        root,
        mergeErr = true
      )(out => show"could not make the binary backup on $router: $out")
      _ <- failUnless(out.contains("Configuration backup saved"))(show"the binary backup failed on $router: $out")
      _ <- Proc.succeedOr(Cmd("scp", "-q", show"$router:$name.backup", newFile.toString), root)(_ =>
        show"could not copy the binary backup from $router."
      )
      _ <- ownerOnly(newFile, directory = false)
      removed <- Proc.status(Cmd("ssh", router, show"/file remove $name.backup"), root)
      _ <- warn(show"could not remove $name.backup on $router.").when(removed != 0)
      _ <- Files.move(newFile, binaryFile, REPLACE_EXISTING).orDie
    yield ()

  def push(git: Git, branch: String): IO[ScriptError, Unit] =
    Proc
      .output(Cmd("git", "-C", git.dir.toString, "log", show"origin/$branch..HEAD"), git.dir)
      .flatMap { (code, log) =>
        git
          .status("push", "--quiet", "origin", branch)
          .flatMap {
            case 0 => printOut(show"Pushed to $repo.")
            case _ =>
              fail(
                show"could not push to $repo; the backup is committed in ${git.dir} and the " +
                  "next run pushes it."
              )
          }
          .when(code == 0 && log.nonEmpty)
          .unit
      }

/** git in the clone. */
final case class Git(dir: Path):
  def cmd(args: String*): Cmd = Cmd("git", "-C", dir.toString) ++ args
  def status(args: String*): IO[ScriptError, Int] = Proc.status(cmd(args*), dir)
  def succeed(args: String*): IO[ScriptError, Unit] = Proc.succeed(cmd(args*), dir)
  def output(args: String*): IO[ScriptError, String] =
    Proc.outputOr(cmd(args*), dir)(_ => show"`git ${args.mkString(" ")}` in $dir failed")
