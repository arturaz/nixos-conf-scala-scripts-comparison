// Command-line parsing with decline.
package nixosconf.lib

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import cats.mtl.syntax.all.*
import com.monovore.decline.Command

object Cli:

  /** Parses `args` with `command`. `--help` prints the help and ends the script
    * (exit 0); a parse error prints it with the errors to stderr (exit 1).
    */
  def parse[A](command: Command[A], args: List[String])(using CanFail): IO[A] =
    IO(sys.env).flatMap { env =>
      command.parse(args, env) match
        case Right(a) => IO.pure(a)
        case Left(help) if help.errors.isEmpty =>
          Console[IO].println(help) *> Exit(ExitCode.Success).raise[IO, A]
        case Left(help) =>
          Console[IO].errorln(help) *> Exit(ExitCode.Error).raise[IO, A]
    }
