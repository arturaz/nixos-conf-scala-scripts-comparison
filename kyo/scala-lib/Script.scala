// Running a script: its entry point, failing with a message, where the repository is.
package nixosconf.lib

import caseapp.core.RemainingArgs
import caseapp.core.help.Help
import caseapp.core.parser.Parser
import kyo.*
import sun.misc.Signal
import yantl.AsString

/** What stops a script early: [[Fail]] or [[Exit]], through `Abort[Stop]`. */
sealed trait Stop

/** Stops the script: `error: <message>` on stderr, exit code `code`. */
final case class Fail(message: String, code: Int = 1) extends Stop

/** Stops the script with exit code `code`, saying nothing more. */
final case class Exit(code: Int) extends Stop

/** What a script's steps need: waiting (for commands, fibers), stopping, files. */
type Script = Async & Abort[Stop] & PathWrite

def fail(message: String)(using Frame): Nothing < Abort[Stop] = Abort.fail(Fail(message))

/** A mistake in the command line: as case-app has its own, exit 1. */
def usage(message: String)(using Frame): Nothing < Abort[Stop] = fail(show"$message (see --help)")

/** `effect` only if `condition` holds. */
def when[S](condition: Boolean)(effect: => Any < S)(using Frame): Unit < S =
  if condition then effect.unit else ()

/** `effect` unless `condition` holds. */
def unless[S](condition: Boolean)(effect: => Any < S)(using Frame): Unit < S = when(!condition)(effect)

/** An environment variable, if set. */
def env(name: String)(using Frame): Maybe[String] < Sync = System.env[String](name)

/** yantl's errors (ValidatorRule's, ours) say what's wrong in their `toString`. */
given [A]: AsString[A] = AsString.fromToString

extension [A](either: Either[String, A])
  /** The value, or a [[Fail]] with the error. */
  def orFail(using Frame): A < Abort[Stop] = Abort.get(either.left.map(Fail(_)))

/** A script's entry point: `object Main extends ScriptApp[Options]`, its options a
  * case class case-app parses (with `--help`; a parse error ends it with exit 1);
  * [[program]] gets the arguments left over. A [[Fail]] ends it with its message, an
  * [[Exit]] with its code, anything else with a stack trace (exit 1).
  *
  * kyo-case-app's runner, on Ctrl-C (SIGINT) or SIGTERM, exits at once, while the
  * interrupted program's finalizers may still be running. So the program runs in a
  * fiber of its own, whose interruption these signals' handlers (replacing kyo's)
  * ask for, and the script ends once it and its finalizers (killing commands,
  * removing temporary files) have finished, with 130 (143 for SIGTERM).
  */
abstract class ScriptApp[T](using Parser[T], Help[T]) extends KyoCaseApp[T]:
  def program(options: T, args: Chunk[String]): Int < (Script & Scope)

  /** The script's file name, for --help's usage line. */
  override def name: String

  /** What --help prints after the options: what the script does, its arguments
    * (case-app parses only the options) and the environment variables it reads.
    */
  def description: String

  override def helpAsked(progName: String, maybeOptions: Either[caseapp.core.Error, T]): Nothing =
    printLine(finalHelp.help(helpFormat, showHidden = false))
    printLine("")
    printLine(description)
    exit(0)

  private def handled(options: T, args: Chunk[String])(using Frame): Int < Async =
    Abort.recover[Stop] {
      case Fail(message, code) => Console.printLineErr(show"error: $message").andThen(code)
      case Exit(code)          => code
    }(
      Abort.recover[FileSystemException](e => fail(e.getMessage))(
        Path.run(Scope.run(program(options, args)))
      )
    )

  /** Which signal ended the program (its exit code), if one did. */
  private val signalled = AtomicRef.Unsafe.init(Maybe.empty[Int])(using AllowUnsafe.embrace.danger)

  private def interruptOnSignals(fiber: Fiber[Int, Any])(using Frame): Unit < Sync =
    Sync.Unsafe.defer {
      for (name, code) <- Seq("INT" -> 130, "TERM" -> 143) do
        Signal.handle(
          Signal(name),
          _ =>
            signalled.set(Present(code))
            fiber.unsafe.interruptDiscard(Result.Panic(Interrupted(summon[Frame], show"SIG$name")))
        )
    }

  run { (options: T, remaining: RemainingArgs) =>
    direct {
      // Scoped, and the exit only after this Scope.run: an interrupted fiber's result
      // is there before its scope has finished releasing (kyo's Scope.Finalizer.close
      // says so, to be fixed there), but the release of a Fiber.init's fiber waits for
      // that scope, the program's nested ones included.
      val code = Scope
        .run(direct {
          val fiber = Fiber.init(handled(options, Chunk.from(remaining.all))).now
          interruptOnSignals(fiber).now
          fiber.getResult.now
        })
        .map(result =>
          Sync.Unsafe.defer(result match
            case Result.Success(code)                                    => code
            case Result.Panic(_: Interrupted) if signalled.get().nonEmpty => signalled.get().getOrElse(1)
            case Result.Panic(e) =>
              e.printStackTrace()
              1
            case Result.Failure(_) => 1
          )
        )
        .now
      Sync.defer[Unit, Any](exit(code)).now
    }
  }

object Repo:

  /** The repository's root: the nearest directory with a flake.nix above the
    * script's source (scala-cli passes the sources' paths in `scala.sources`), so
    * a script works from any directory.
    */
  def root(using Frame): Path < (Sync & Abort[Stop] & PathRead) =
    direct {
      val cwd = Path.cwd.now
      val sources = System.property[String]("scala.sources").now
      val starts = sources.toList
        .flatMap(_.split(java.io.File.pathSeparatorChar))
        .filter(_.nonEmpty)
        .map(resolve(cwd, _)) :+ cwd
      val found = starts.flatMap(ancestors).filter(dir => (dir / "flake.nix").isRegularFile.now)
      found.headOption.toRight("can't find the repository (no flake.nix above the script)").orFail.now
    }

  /** `path` as given, or relative to `cwd`. */
  def resolve(cwd: Path, path: String)(using Frame): Path =
    if path.startsWith("/") then Path(path) else cwd / path

  /** `path` and the directories above it. */
  def ancestors(path: Path): List[Path] =
    path :: path.parent.fold(Nil)(ancestors)
