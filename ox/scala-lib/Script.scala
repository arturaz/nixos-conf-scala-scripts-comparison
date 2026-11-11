// Running a script: its entry point, stopping it with a message, where the repository is.
package nixosconf.lib

import cats.syntax.all.*
import ox.{ExitCode, Ox, OxApp}
import scala.concurrent.duration.*
import yantl.AsString

/** Why a script stops before its end. Not an exception: whatever can stop the script
  * returns `Either[ScriptError, A]`, written as Ox's `either { ... }` blocks, in which
  * `.ok()` takes a step's value or leaves the block with its error (Scala 3's
  * boundary/break). A thrown exception is a defect (a bug, or something no one
  * expected, e.g. a file operation failing), ending the script with a stack trace.
  *
  * `.ok()` breaks out with an exception (boundary's `Break`): never catch every
  * exception (`case NonFatal(_)`, `Try`) around it, and never call it in a fork
  * (another thread) of the block's own `either`.
  */
sealed trait ScriptError

/** `error: <message>` on stderr, exit code `code`. */
final case class Fail(message: String, code: ExitCode = ExitCode.Failure()) extends ScriptError

/** Exit code `code`, saying nothing more (what was wrong has been said). */
final case class Exit(code: ExitCode) extends ScriptError

/** Stops the script with `error: <message>` (`.ok()` it in an `either` block). */
def fail[A](message: String): Either[ScriptError, A] = Left(Fail(message))

/** Stops the script with `error: <message>` unless `condition` holds. */
def failUnless(condition: Boolean)(message: => String): Either[ScriptError, Unit] =
  Either.cond(condition, (), Fail(message))

/** An exit code from a process's. */
def exitCode(code: Int): ExitCode = if code == 0 then ExitCode.Success else ExitCode.Failure(code)

/** `body`'s result and how long it took (by the wall clock). */
def timed[A](body: => A): (FiniteDuration, A) =
  val start = System.nanoTime()
  val a = body
  ((System.nanoTime() - start).nanos, a)

/** yantl's errors (ValidatorRule's, ours) say what's wrong in their `toString`. */
given [A]: AsString[A] = AsString.fromToString

extension [A](either: Either[String, A])
  /** The value, or a [[Fail]] with the error. */
  def orFail: Either[ScriptError, A] = either.leftMap(Fail(_))

/** A script's entry point: `object Main extends ScriptApp`. A [[Fail]] ends it with
  * its message, an [[Exit]] with its code; an exception with its stack trace (exit 1).
  * Ctrl-C (SIGINT, or SIGTERM) interrupts the program (OxApp's shutdown hook), which
  * runs its finalizers and exits 130 (OxApp's default would be 0).
  */
trait ScriptApp extends OxApp:
  def program(args: List[String])(using Ox): Either[ScriptError, ExitCode]

  override protected def settings: OxApp.Settings =
    OxApp.Settings.Default.copy(interruptedExitCode = ExitCode.Failure(130))

  final override def run(args: Vector[String])(using Ox): ExitCode =
    program(args.toList) match
      case Right(code)               => code
      case Left(Fail(message, code)) => Console.err.println(show"error: $message"); code
      case Left(Exit(code))          => code

object Repo:

  /** The repository's root: the nearest directory with a flake.nix above the
    * script's source (scala-cli passes the sources' paths in `scala.sources`), so
    * a script works from any directory.
    */
  def root: Either[ScriptError, os.Path] =
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
      .orFail
