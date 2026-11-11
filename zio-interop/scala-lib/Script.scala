// Running a script: its entry point, stopping it with a message, where the repository is.
package nixosconf.lib

import cats.syntax.all.*
import yantl.AsString
import zio.{Console, ExitCode, IO, UIO, ZIO, ZIOAppArgs, ZIOAppDefault}

/** Why a script stops before its end: the error channel of its steps,
  * `IO[ScriptError, A]` (ZIO's `IO`, a ZIO failure). cats' syntax works on them
  * (`traverse_`, `whenA`, `ifM`, `parTraverse`...) through zio-interop-cats' instances
  * (`import zio.interop.catz.*`). Anything else that goes wrong is a defect (a bug, or
  * something no one expected, e.g. a file operation failing: `.orDie`), ending the
  * script with a stack trace.
  */
sealed trait ScriptError

/** `error: <message>` on stderr, exit code `code`. */
final case class Fail(message: String, code: ExitCode = ExitCode.failure) extends ScriptError

/** Exit code `code`, saying nothing more (what was wrong has been said). (Not
  * `zio.Exit`: scripts import from zio by name, never `zio.*`.)
  */
final case class Exit(code: ExitCode) extends ScriptError

/** A step that can stop the script, as one type constructor (for cats' `.pure[ScriptIO]`). */
type ScriptIO[A] = IO[ScriptError, A]

/** Stops the script with `error: <message>`. */
def fail(message: String): IO[ScriptError, Nothing] = ZIO.fail(Fail(message))

/** Stops the script with `error: <message>` unless `condition` holds. */
def failUnless(condition: Boolean)(message: => String): IO[ScriptError, Unit] =
  if condition then ZIO.unit else fail(message)

/** A line on stdout. A failure to print is a defect. */
def printOut(line: String): UIO[Unit] = Console.printLine(line).orDie

/** A line on stderr. A failure to print is a defect. */
def printErr(line: String): UIO[Unit] = Console.printLineError(line).orDie

/** Something blocking (a file operation), its exceptions defects. */
def blocking[A](a: => A): UIO[A] = ZIO.attemptBlocking(a).orDie

/** yantl's errors (ValidatorRule's, ours) say what's wrong in their `toString`. */
given [A]: AsString[A] = AsString.fromToString

extension [A](either: Either[String, A])
  /** The value, or a [[Fail]] with the error. */
  def orFail: IO[ScriptError, A] = ZIO.fromEither(either.leftMap(Fail(_)))

/** A script's entry point: `object Main extends ScriptApp`. A [[Fail]] ends it with
  * its message, an [[Exit]] with its code; a defect with ZIO's log of it (exit 1).
  * Ctrl-C (SIGINT, or SIGTERM) interrupts the program through ZIOApp's shutdown hook,
  * which waits for its finalizers; the JVM then exits as the signal has it (130, 143).
  */
trait ScriptApp extends ZIOAppDefault:
  def program(args: List[String]): IO[ScriptError, ExitCode]

  final override def run: ZIO[ZIOAppArgs, Nothing, Unit] =
    getArgs
      .flatMap(args => program(args.toList))
      .catchAll {
        case Fail(message, code) => printErr(show"error: $message").as(code)
        case Exit(code)          => ZIO.succeed(code)
      }
      // ZIOApp's own exit code is 0 for any success: ours is set by exiting.
      .flatMap(exit)

object Repo:

  /** The repository's root: the nearest directory with a flake.nix above the
    * script's source (scala-cli passes the sources' paths in `scala.sources`), so
    * a script works from any directory.
    */
  val root: IO[ScriptError, os.Path] =
    blocking {
      val sources = sys.props
        .get("scala.sources")
        .toList
        .flatMap(_.split(java.io.File.pathSeparatorChar))
        .filter(_.nonEmpty)
        .map(os.Path(_, os.pwd))
      (sources :+ os.pwd).iterator
        .flatMap(p => Iterator.iterate(p)(_ / os.up).takeWhile(_ != os.root))
        .find(dir => os.isFile(dir / "flake.nix"))
        .toRight("can't find the repository (no flake.nix above the script)")
    }.flatMap(_.orFail)
