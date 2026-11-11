// Running a script: its entry point, stopping it with a message, where the repository is.
package nixosconf.lib

import yantl.AsString
import zio.*
import zio.nio.file.{Files, Path}

/** Why a script stops before its end: the error channel of its steps,
  * `IO[ScriptError, A]` (a ZIO failure). Anything else that goes wrong is a defect
  * (a bug, or something no one expected, e.g. a file operation failing: `.orDie`),
  * ending the script with a stack trace.
  */
sealed trait ScriptError

/** `error: <message>` on stderr, exit code `code`. */
final case class Fail(message: String, code: ExitCode = ExitCode.failure) extends ScriptError

/** Exit code `code`, saying nothing more (what was wrong has been said). Not named
  * `Exit`, which is ZIO's (a fiber's result).
  */
final case class Halt(code: ExitCode) extends ScriptError

/** Stops the script with `error: <message>`. */
def fail(message: String): IO[ScriptError, Nothing] = ZIO.fail(Fail(message))

/** Stops the script with `error: <message>` unless `condition` holds. */
def failUnless(condition: Boolean)(message: => String): IO[ScriptError, Unit] =
  if condition then ZIO.unit else fail(message)

/** A line on stdout. A failure to print is a defect. */
def printOut(line: String): UIO[Unit] = Console.printLine(line).orDie

/** A line on stderr. A failure to print is a defect. */
def printErr(line: String): UIO[Unit] = Console.printLineError(line).orDie

/** An environment variable, if set. */
def env(name: String): UIO[Option[String]] = System.env(name).orDie

/** yantl's errors (ValidatorRule's, ours) say what's wrong in their `toString`. */
given [A]: AsString[A] = AsString.fromToString

extension [A](either: Either[String, A])
  /** The value, or a [[Fail]] with the error. */
  def orFail: IO[ScriptError, A] = ZIO.fromEither(either).mapError(Fail(_))

/** A script's entry point: `object Main extends ScriptApp`. A [[Fail]] ends it with
  * its message, a [[Halt]] with its code; a defect with ZIO's log of it (exit 1).
  * Ctrl-C (SIGINT, or SIGTERM) interrupts the program through ZIOApp's shutdown hook,
  * which waits for its finalizers (killing commands, removing temporary files); the
  * JVM then exits as the signal has it (130, 143).
  */
trait ScriptApp extends ZIOAppDefault:
  def program(args: List[String]): IO[ScriptError, ExitCode]

  final override def run: ZIO[ZIOAppArgs, Nothing, Unit] =
    getArgs
      .flatMap(args => program(args.toList))
      .catchAll {
        case Fail(message, code) => printErr(show"error: $message").as(code)
        case Halt(code)          => ZIO.succeed(code)
      }
      // ZIOApp's own exit code is 0 for any success: ours is set by exiting.
      .flatMap(exit)

object Repo:

  /** The repository's root: the nearest directory with a flake.nix above the
    * script's source (scala-cli passes the sources' paths in `scala.sources`), so
    * a script works from any directory.
    */
  val root: IO[ScriptError, Path] =
    for
      cwd <- ZIO.attempt(Path(java.lang.System.getProperty("user.dir"))).orDie
      sources <- System.property("scala.sources").orDie
      starts = sources.toList
        .flatMap(_.split(java.io.File.pathSeparatorChar))
        .filter(_.nonEmpty)
        .map(cwd / _) :+ cwd
      found <- ZIO.collectFirst(starts.flatMap(ancestors))(dir =>
        Files.isRegularFile(dir / "flake.nix").map(Option.when(_)(dir))
      )
      root <- found.toRight("can't find the repository (no flake.nix above the script)").orFail
    yield root

  /** `path` and the directories above it, the root (`/`) left out. */
  def ancestors(path: Path): List[Path] =
    path.normalize.parent.fold(Nil)(parent => path.normalize :: ancestors(parent))
