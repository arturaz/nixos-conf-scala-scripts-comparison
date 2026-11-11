// Running a script: its entry point, stopping it with a message, where the repository is.
package nixosconf.lib

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.Console
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import yantl.AsString

/** Why a script stops before its end. Raised through cats-mtl's `Raise` (see
  * [[CanFail]]), not thrown: a thrown exception is a defect (a bug, or something no
  * one expected, e.g. a file operation failing), ending the script with a stack
  * trace.
  */
sealed trait ScriptError

/** `error: <message>` on stderr, exit code `code`. */
final case class Fail(message: String, code: ExitCode = ExitCode.Error) extends ScriptError

/** Exit code `code`, saying nothing more (what was wrong has been said). */
final case class Exit(code: ExitCode) extends ScriptError

/** What a step that can stop the script needs: `def f(...)(using CanFail): IO[A]`.
  * The steps stay plain `IO`s (every cats combinator works on them); [[ScriptApp]]
  * provides it, with `Handle.allow`.
  *
  * cats-mtl carries a raised error through `IO` as a private exception that only its
  * own `rescue` catches. So, inside a script:
  *   - never handle every error (`.attempt`, `.handleError(_ => ...)`,
  *     `.handleErrorWith`, `.redeem`, `.orElse`) around something that can raise: it
  *     would swallow the [[ScriptError]] too. Catch the exceptions meant
  *     (`.recover { case _: java.io.IOException => ... }`), and [[ScriptError]]s with
  *     `Handle.allow[ScriptError](...).rescue`;
  *   - don't let an `IO` that raises outlive the block that provided its `CanFail`
  *     (e.g. a fiber still running after it): its error would end the script as an
  *     unexplained `cats.mtl.Handle$Submarine`.
  */
type CanFail = Raise[IO, ScriptError]

/** Stops the script with `error: <message>`. */
def fail[A](message: String)(using r: CanFail): IO[A] = r.raise(Fail(message))

/** Stops the script with `error: <message>` unless `condition` holds. */
def failUnless(condition: Boolean)(message: => String)(using CanFail): IO[Unit] =
  if condition then IO.unit else fail(message)

/** yantl's errors (ValidatorRule's, ours) say what's wrong in their `toString`. */
given [A]: AsString[A] = AsString.fromToString

extension [A](either: Either[String, A])
  /** The value, or a [[Fail]] with the error. */
  def orFail(using CanFail): IO[A] = either.fold(fail, IO.pure)

/** A script's entry point: `object Main extends ScriptApp`. A [[Fail]] ends it with
  * its message, an [[Exit]] with its code.
  */
trait ScriptApp extends IOApp:
  def program(args: List[String])(using CanFail): IO[ExitCode]

  final override def run(args: List[String]): IO[ExitCode] =
    Handle
      .allow[ScriptError](program(args))
      .rescue {
        case Fail(message, code) => Console[IO].errorln(show"error: $message").as(code)
        case Exit(code)          => IO.pure(code)
      }

object Repo:

  /** The repository's root: the nearest directory with a flake.nix above the
    * script's source (scala-cli passes the sources' paths in `scala.sources`), so
    * a script works from any directory.
    */
  def root(using CanFail): IO[os.Path] =
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
    }.flatMap(_.orFail)
