// Running a script: its entry point, stopping it with a message, where the repository is.
package nixosconf.lib

import cats.data.EitherT
import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.Console
import cats.syntax.all.*
import yantl.AsString

/** Why a script stops before its end. Not an exception: whatever can stop the script
  * returns `IO[Either[ScriptError, A]]`, composed with cats' `EitherT` (see
  * [[toEitherT]], [[liftTScriptError]]); a thrown exception is a defect (a bug, or
  * something no one expected, e.g. a file operation failing), ending the script with a
  * stack trace.
  */
sealed trait ScriptError

/** `error: <message>` on stderr, exit code `code`. */
final case class Fail(message: String, code: ExitCode = ExitCode.Error) extends ScriptError

/** Exit code `code`, saying nothing more (what was wrong has been said). */
final case class Exit(code: ExitCode) extends ScriptError

/** A step of an `EitherT` for-comprehension: an `IO` that can stop the script. */
type ScriptT[A] = EitherT[IO, ScriptError, A]

/** Stops the script with `error: <message>`. */
def fail[A](message: String): IO[Either[ScriptError, A]] = IO.pure(Left(Fail(message)))

/** [[fail]], as a step of an `EitherT` for-comprehension. */
def failT[A](message: String): EitherT[IO, ScriptError, A] = EitherT.leftT(Fail(message))

/** Stops the script with `error: <message>` unless `condition` holds. */
def failUnless(condition: Boolean)(message: => String): EitherT[IO, ScriptError, Unit] =
  EitherT.cond(condition, (), Fail(message))

extension [F[_], E, A](fa: F[Either[E, A]])
  /** As a step of an `EitherT` for-comprehension. */
  def toEitherT: EitherT[F, E, A] = EitherT(fa)

extension [A](io: IO[A])
  /** As a step of an `EitherT` for-comprehension that doesn't stop the script. */
  def liftTScriptError: EitherT[IO, ScriptError, A] = EitherT.liftF(io)

/** yantl's errors (ValidatorRule's, ours) say what's wrong in their `toString`. */
given [A]: AsString[A] = AsString.fromToString

extension [A](either: Either[String, A])
  /** The value, or a [[Fail]] with the error. */
  def orFail: Either[ScriptError, A] = either.leftMap(Fail(_))

/** A script's entry point: `object Main extends ScriptApp`. A [[Fail]] ends it with
  * its message, an [[Exit]] with its code.
  */
trait ScriptApp extends IOApp:
  def program(args: List[String]): IO[Either[ScriptError, ExitCode]]

  final override def run(args: List[String]): IO[ExitCode] =
    program(args).flatMap {
      case Right(code)               => IO.pure(code)
      case Left(Fail(message, code)) => Console[IO].errorln(show"error: $message").as(code)
      case Left(Exit(code))          => IO.pure(code)
    }

object Repo:

  /** The repository's root: the nearest directory with a flake.nix above the
    * script's source (scala-cli passes the sources' paths in `scala.sources`), so
    * a script works from any directory.
    */
  val root: IO[Either[ScriptError, os.Path]] =
    IO.blocking {
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
    }
