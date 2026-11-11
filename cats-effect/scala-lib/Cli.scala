// Command-line parsing with decline.
package nixosconf.lib

import cats.effect.{ExitCode, IO}
import cats.effect.std.Console
import com.monovore.decline.Command

object Cli:

  /** Parses `args` with `command`. `--help` prints the help and ends the script
    * (exit 0); a parse error prints it with the errors to stderr (exit 1).
    */
  def parse[A](command: Command[A], args: List[String]): IO[Either[ScriptError, A]] =
    IO(sys.env).flatMap { env =>
      command.parse(args, env) match
        case Right(a) => IO.pure(Right(a))
        case Left(help) if help.errors.isEmpty =>
          Console[IO].println(help).as(Left(Exit(ExitCode.Success)))
        case Left(help) =>
          Console[IO].errorln(help).as(Left(Exit(ExitCode.Error)))
    }
