#!/usr/bin/env bash
//usr/bin/env true; exec "$(dirname "$0")/scala-lib/run" "$0" "$@" # bash runs this line; Scala sees a comment
// Refuses to let a secret out: fails if any file in secrets/ isn't a fully
// sops-encrypted YAML file, or if any other file holds private-key material or an
// obvious credential (an API token, a password hash, a URL with a password in it...).
// The repo is public, and whatever is pushed stays public for good (see
// docs/secrets.md).
//
// It checks what would be pushed: the working copy (for jj: the working-copy commit,
// which jj snapshots first; for git: every file that isn't ignored, tracked or not) and
// every commit not on a remote yet, so a secret committed and then deleted is caught
// too; in each, the files it adds or changes (what it leaves alone is either public
// already or comes from another of these commits, checked there). jj runs no git
// hooks, so this isn't one: test.scala (with the evaluation) and deploy.scala run it, or
// run it by hand. It prints where, never what it found.
// Exit codes: 0 nothing found, 1 something found, 2 it couldn't look (any git or jj
// failure: it never passes without having looked).
//
// A line that must keep a pattern match (a test's made-up password) gets a comment
// with `secret-scan: allow` on the same line.
// Usage: --help.
//> using file scala-lib

import cats.data.{EitherT, NonEmptyVector}
import cats.effect.{ExitCode, IO, Resource}
import cats.effect.std.Console
import cats.syntax.all.*
import com.monovore.decline.{Command, Opts}
import nixosconf.lib.*
import scala.util.matching.Regex

/** A commit (or, for git without jj, the working copy's tree) to check. */
final case class Rev(id: String):
  def short: String = id.take(12)

/** A line a [[Rev]]'s diff adds. */
final case class AddedLine(rev: Rev, path: String, line: Int, text: String)

/** What looks like a secret: `name` for the message. */
final case class Pattern(name: String, regex: Regex)

object Main extends ScriptApp:
  val command: Command[Unit] = Command(
    "check-secrets.scala",
    "Fails (exit 1) if what would be pushed has a secret: a file in secrets/ that " +
      "isn't fully sops-encrypted, a file named like a key, or a line that looks like " +
      "a private key or credential (unless it says `secret-scan: allow`). Exit 2: it " +
      "couldn't look."
  )(Opts.unit)

  def program(args: List[String]): IO[Either[ScriptError, ExitCode]] =
    (for
      root <- Repo.root.toEitherT
      _ <- Cli.parse(command, args).toEitherT
      code <- Scan.revs(root).use(_.toEitherT.flatMap(Scan(root, _).run).value).toEitherT
    yield code).value

/** It must never pass without having looked: any git or jj failure stops it, as a
  * failure (exit 2).
  */
def unread[A](message: String): IO[Either[ScriptError, A]] =
  Console[IO]
    .errorln(show"check-secrets: $message; nothing was checked, so this counts as a failure")
    .as(Left(Exit(ExitCode(2))))

/** [[unread]], as a step of an `EitherT` for-comprehension. */
def unreadT[A](message: String): EitherT[IO, ScriptError, A] = unread(message).toEitherT

/** git in the repository: `env` has GIT_DIR (jj) or GIT_INDEX_FILE. */
final case class Git(root: os.Path, env: Map[String, String]):
  def cmd(args: Seq[String]): os.proc = os.proc("git", args)

  def apply(args: String*): EitherT[IO, ScriptError, String] = raw(args*)

  /** Its output as it is (blobs keep their trailing newlines). */
  def raw(args: String*): EitherT[IO, ScriptError, String] =
    EitherT(Proc.output(cmd(args), root, env = env, raw = true).flatMap {
      case (0, out) => IO.pure(Right(out))
      case _        => unread(show"`git ${args.mkString(" ")}` failed")
    })

  /** NUL-separated output (`-z`: paths as they are, never quoted). */
  def entries(args: String*): EitherT[IO, ScriptError, Vector[String]] =
    raw(args*).map(_.split('\u0000').toVector.filter(_.nonEmpty))

  def ok(args: String*): IO[Boolean] =
    Proc.status(cmd(args), root, Proc.devNull, Proc.devNull, env).map(_ == 0)

  val hasHead: IO[Boolean] = ok("rev-parse", "-q", "--verify", "HEAD")

/** What [[Scan]] looks at. */
final case class Revs(git: Git, revs: NonEmptyVector[Rev])

object Scan:

  /** The working copy, as a git tree/commit, and the commits not on a remote yet. */
  def revs(root: os.Path): Resource[IO, Either[ScriptError, Revs]] =
    def lines(s: String) = s.linesIterator.map(_.trim).filter(_.nonEmpty).map(Rev(_)).toVector
    def nonEmpty(git: Git, revs: Vector[Rev]): EitherT[IO, ScriptError, Revs] =
      NonEmptyVector.fromVector(revs) match
        case Some(revs) => Revs(git, revs).pure[ScriptT]
        case None       => unreadT("found no commit to check")
    def run(proc: os.proc, what: String, env: Map[String, String] = Map.empty) =
      Proc
        .output(proc, root, env = env)
        .flatMap {
          case (0, out) => IO.pure(Right(out))
          case _        => unread[String](show"$what failed")
        }
        .toEitherT
    Resource.eval(IO.blocking(os.isDir(root / ".jj"))).flatMap {
      case true =>
        Resource.eval(
          (for
            // Snapshots the working copy into its commit (jj ignores what .gitignore
            // ignores).
            revs <- run(
              os.proc(
                "jj",
                "log",
                "--no-graph",
                "-r",
                "(remote_bookmarks()..(@ | bookmarks())) ~ root()",
                "-T",
                """commit_id ++ "\n""""
              ),
              "jj log"
            )
            // The commits are in the repository's git store, which a second jj
            // workspace (jj workspace add) has no .git of its own to find.
            gitDir <- run(os.proc("jj", "git", "root"), "jj git root")
            result <- nonEmpty(Git(root, Map("GIT_DIR" -> gitDir)), lines(revs))
          yield result).value
        )
      case false =>
        // Every file that isn't ignored, through a throwaway index (the real one is
        // left alone).
        Resource
          .make(IO.blocking(os.temp.dir()))(dir => IO.blocking(os.remove.all(dir)))
          .evalMap { dir =>
            val withIndex = Git(root, Map("GIT_INDEX_FILE" -> (dir / "index").toString))
            val git = Git(root, Map.empty)
            (for
              _ <- withIndex("add", "-A")
              tree <- withIndex("write-tree")
              unpushed <- git.hasHead.liftTScriptError.ifM(
                git("rev-list", "HEAD", "--not", "--remotes"),
                "".pure[ScriptT]
              )
              result <- nonEmpty(git, lines(tree) ++ lines(unpushed))
            yield result).value
          }
    }

  /** A file in secrets/ must be one of these. */
  val secretsFile: Regex = "secrets/[^/]*\\.yaml".r

  /** Files named like keys or credential stores, anywhere. */
  val keyNames: Regex =
    ("(^|/)(id_(rsa|dsa|ecdsa|ed25519)(_sk)?|ssh_host_[a-z0-9]+_key|" +
      "[^/]*\\.(pem|key|p12|pfx|jks|keystore|kdbx|ovpn)|\\.env(\\.[^/]*)?|\\.netrc|" +
      "\\.git-credentials|\\.npmrc|\\.pypirc|credentials(\\.json)?)$").r

  /** What private keys and credentials look like. Written so that this file doesn't
    * match itself.
    */
  val patterns: Vector[Pattern] =
    val q = "\"'" // either quote
    Vector(
      "private key" -> "-----BEGIN ([A-Z0-9]+ )*PRIVATE KEY( BLOCK)?-----",
      "age secret key" -> "AGE-SECRET-KEY-1[0-9A-Z]{20,}",
      "GitHub token" -> "(gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})",
      "GitLab token" -> "glpat-[A-Za-z0-9_-]{20,}",
      "Anthropic API key" -> "sk-ant-[A-Za-z0-9_-]{20,}",
      "OpenAI-style API key" -> "sk-(proj-|svcacct-)?[A-Za-z0-9_-]{32,}",
      "AWS access key" -> "(AKIA|ASIA)[0-9A-Z]{16}",
      "Slack token" -> "xox[abposr]-[A-Za-z0-9-]{10,}",
      "Google API key" -> "AIza[0-9A-Za-z_-]{35}",
      "Stripe key" -> "[rs]k_live_[0-9A-Za-z]{20,}",
      "password hash" ->
        show"[$$](1|5|6|y|gy|7|2[aby]|argon2(id|i|d)|scrypt|pbkdf2[a-z0-9-]*)[$$][^\\s$q]{8,}",
      "URL with a password" -> show"[a-z][a-z0-9+.-]*://[^/@:\\s$q$$]+:[^/@\\s$q$$]+@",
      "credential assignment" ->
        (show"(?i)(password|passwd|secret|token|api[_-]?key)[$q]?\\s*[:=]\\s*[$q]" +
          show"[^$q$$/\\s{}<>]{12,}[$q]")
    ).map((name, regex) => Pattern(name, regex.r))

  /** sops's encrypted values. */
  val enc = "ENC\\[AES256_GCM,data:[A-Za-z0-9+/=]*,iv:[A-Za-z0-9+/=]+,tag:[A-Za-z0-9+/=]+,type:[a-z]+\\]"
  val sopsKey = "[^:#\"' ]+:"
  val encryptedLines: Vector[Regex] = Vector(
    show"[ \t]*#$enc", // a comment
    show"[ \t]*(- )*$sopsKey[ \t]*", // a key with nothing after it (a mapping)
    show"[ \t]*(- )*$sopsKey[ \t]+$enc", // a value
    show"[ \t]*- $enc" // a list item
  ).map(_.r)
  val sopsStart: Regex = "sops:[ \t]*".r
  val sopsMac: Regex = show"    mac: $enc".r
  val sopsAge: Regex = "    age:[ \t]*".r

  /** Why `yaml` isn't fully sops-encrypted, if it isn't. Line by line, failing
    * closed: outside the `sops:` metadata, each line must be an encrypted value, an
    * encrypted comment or a key with nothing after it (a mapping holding encrypted
    * values); a plaintext value, comment or multi-line string fails. The metadata
    * must have the MAC and an age key.
    */
  def notEncrypted(yaml: String): Option[String] =
    // Lines as awk had them: split on \n only (a \r stays and fails), no empty last
    // one after a final newline.
    val lines = yaml.split("\n", -1).toVector
    val records = if yaml.endsWith("\n") || yaml.isEmpty then lines.dropRight(1) else lines
    final case class State(inSops: Boolean, seen: Boolean, mac: Boolean, age: Boolean)
    records.zipWithIndex
      .foldLeft[Either[String, State]](Right(State(false, false, false, false))) {
        case (Right(state), (line, i)) =>
          if sopsStart.matches(line) then Right(state.copy(inSops = true, seen = true))
          else
            val inSops = state.inSops && !line.headOption.exists(c => c != ' ' && c != '\t')
            if inSops then
              Right(
                state.copy(
                  mac = state.mac || sopsMac.matches(line),
                  age = state.age || sopsAge.matches(line)
                )
              )
            else if encryptedLines.exists(_.matches(line)) then Right(state.copy(inSops = false))
            else Left(show"line ${i + 1} is not encrypted")
        case (left, _) => left
      }
      .flatMap(state =>
        if !state.seen then Left("no sops metadata (never encrypted?)")
        else if !state.mac then Left("no sops MAC")
        else if !state.age then Left("no age recipients")
        else Right(())
      )
      .left
      .toOption

  /** A path as git quotes it in a patch's `+++ "b/..."` (C-style escapes, octal
    * bytes), unquoted.
    */
  def unquote(quoted: String): String =
    val bytes = Vector.newBuilder[Byte]
    def go(i: Int): Unit =
      if i < quoted.length then
        quoted(i) match
          case '\\' if i + 1 < quoted.length =>
            quoted(i + 1) match
              case d if d.isDigit =>
                bytes += Integer.parseInt(quoted.substring(i + 1, i + 4), 8).toByte
                go(i + 4)
              case c =>
                val escaped = c match
                  case 'n' => '\n'
                  case 't' => '\t'
                  case 'r' => '\r'
                  case 'a' => '\u0007'
                  case 'b' => '\b'
                  case 'f' => '\f'
                  case 'v' => '\u000b'
                  case other => other
                bytes ++= escaped.toString.getBytes("UTF-8")
                go(i + 2)
          case c =>
            bytes ++= c.toString.getBytes("UTF-8")
            go(i + 1)
    go(0)
    String(bytes.result().toArray, "UTF-8")

  val hunk: Regex = """@@ -\S+ \+(\d+)(,\d+)? @@.*""".r
  val plainPath: Regex = """\+\+\+ b/(.*)""".r
  val quotedPath: Regex = "\\+\\+\\+ \"b/(.*)\"".r

  /** The lines a patch (`-U0`) adds, with their paths and line numbers. Headers are
    * told apart from added lines by where they are (between `diff --git` and the
    * first `@@`), so an added line starting with `++ b/` isn't taken for one.
    */
  def addedLines(rev: Rev, patch: String): Vector[AddedLine] =
    final case class State(header: Boolean, path: Option[String], line: Int)
    patch
      .split("\n", -1)
      .foldLeft((State(false, None, 0), Vector.empty[AddedLine])) { case ((state, out), l) =>
        if l.startsWith("diff --git ") then (State(true, None, 0), out)
        else if state.header then
          l match
            case plainPath(path)  => (state.copy(path = Some(path)), out)
            case quotedPath(path) => (state.copy(path = Some(unquote(path))), out)
            case hunk(start, _)   => (state.copy(header = false, line = start.toInt), out)
            case _                => (state, out)
        else
          l match
            case hunk(start, _) => (state.copy(line = start.toInt), out)
            case _ if l.startsWith("+") =>
              (
                state.copy(line = state.line + 1),
                state.path.fold(out)(path => out :+ AddedLine(rev, path, state.line, l.drop(1)))
              )
            case _ => (state, out)
      }
      ._2

final case class Scan(root: os.Path, found: Revs):
  import Scan.*
  val git: Git = found.git
  val revs: Vector[Rev] = found.revs.toVector

  def where(rev: Rev, path: String): String = show"$path (in ${rev.short})"

  def run: EitherT[IO, ScriptError, ExitCode] =
    for
      changed <- revs.traverse(rev => changedFiles(rev).tupleLeft(rev))
      added <- revs.flatTraverse(rev => diffArgs(rev).flatMap(patch(rev, _)))
      sops <- changed.flatTraverse((rev, paths) => secretsFiles(rev, paths)).flatMap(secretsProblems)
      names = changed.flatMap((rev, paths) =>
        paths.filter(keyNames.findFirstIn(_).isDefined).map(path =>
          show"${where(rev, path)}: named like a private key or credentials file"
        )
      )
      problems = (sops ++ names ++ patternProblems(added)).toVector
      code <- (
        if problems.isEmpty then
          Console[IO]
            .errorln(show"check-secrets: OK (what ${revs.size} commit(s) not pushed yet add or change)")
            .as(ExitCode.Success)
        else
          Console[IO].errorln("check-secrets: refusing; this repo is public, and these would be pushed:") *>
            problems.traverse_(p => Console[IO].errorln(show"  $p")) *>
            Console[IO]
              .errorln(
                "Put secrets into sops (docs/secrets.md); for a commit not pushed yet, remove " +
                  "it from that commit (jj edit / jj squash), not just from a later one."
              )
              .as(ExitCode.Error)
      ).liftTScriptError
    yield code

  def isCommit(rev: Rev): IO[Boolean] = git.ok("cat-file", "-e", show"${rev.id}^{commit}")

  /** The files a rev adds or changes, relative to its parent(s) (git without jj: the
    * working copy's tree relative to HEAD).
    */
  def changedFiles(rev: Rev): EitherT[IO, ScriptError, Vector[String]] =
    git
      .ok("cat-file", "-e", show"${rev.id}^{tree}")
      .liftTScriptError
      .ifM(().pure[ScriptT], unreadT(show"git can't read ${rev.id}")) *>
      isCommit(rev).liftTScriptError
        .ifM(
          git.entries("diff-tree", "-z", "--root", "-m", "-r", "--no-commit-id", "--name-only", "--diff-filter=d", rev.id),
          git.hasHead.liftTScriptError.ifM(
            git.entries("diff-tree", "-z", "-r", "--name-only", "--diff-filter=d", "HEAD", rev.id),
            git.entries("ls-tree", "-z", "-r", "--name-only", rev.id)
          )
        )
        .map(_.distinct.sorted)

  /** What to diff a rev against, for the lines it adds. */
  def diffArgs(rev: Rev): EitherT[IO, ScriptError, Seq[String]] =
    isCommit(rev).liftTScriptError.ifM(
      Seq("--root", "-m", rev.id).pure[ScriptT],
      git.hasHead.liftTScriptError.ifM(
        Seq("HEAD", rev.id).pure[ScriptT],
        git("hash-object", "-t", "tree", "/dev/null").map(empty => Seq(empty.trim, rev.id))
      )
    )

  /** The lines a rev adds (renames off: a moved file is all new; binary files have
    * none).
    */
  def patch(rev: Rev, args: Seq[String]): EitherT[IO, ScriptError, Vector[AddedLine]] =
    git(
      Seq("diff-tree", "-r", "-p", "-U0", "--no-color", "--no-ext-diff", "--no-renames", "--no-commit-id") ++ args*
    ).map(addedLines(rev, _))

  /** The files in secrets/ a rev adds or changes, as (path, blob). */
  def secretsFiles(rev: Rev, paths: Vector[String]): EitherT[IO, ScriptError, Vector[(Rev, String, String)]] =
    val inSecrets = paths.filter(_.startsWith("secrets/"))
    if inSecrets.isEmpty then EitherT.pure(Vector.empty)
    else
      git
        .entries(Seq("ls-tree", "-z", "-r", rev.id, "--") ++ inSecrets.map(p => show":(literal)$p")*)
        .map(_.map { entry =>
          val (meta, tabPath) = entry.span(_ != '\t')
          (rev, tabPath.drop(1), meta.split(' ').last)
        })

  /** secrets/: only sops-encrypted secrets/<name>.yaml files. Each file once, in the
    * first rev that has it.
    */
  def secretsProblems(files: Vector[(Rev, String, String)]): EitherT[IO, ScriptError, Vector[String]] =
    files
      .distinctBy((_, path, blob) => (path, blob))
      .flatTraverse { (rev, path, blob) =>
        if !secretsFile.matches(path) then
          Vector(show"${where(rev, path)}: only sops-encrypted secrets/*.yaml files belong in secrets/").pure[ScriptT]
        else
          git
            .raw("cat-file", "blob", blob)
            .map(notEncrypted(_).toVector.map(why => show"${where(rev, path)}: not fully sops-encrypted: $why"))
      }

  /** What private keys and credentials look like, in every text file but
    * secrets/<name>.yaml (their ciphertext is random, and [[secretsProblems]] checked
    * them). The text is only checked, never printed.
    */
  def patternProblems(added: Vector[AddedLine]): Vector[String] =
    val candidates = added.filterNot(a =>
      (a.path.startsWith("secrets/") && a.path.endsWith(".yaml")) ||
        a.text.contains("secret-scan: allow")
    )
    patterns
      .flatMap(p =>
        candidates
          .filter(a => p.regex.findFirstIn(a.text).isDefined)
          .distinctBy(a => (a.path, a.line))
          .map(a => show"${where(a.rev, a.path)}, line ${a.line}: looks like a ${p.name}")
      )
