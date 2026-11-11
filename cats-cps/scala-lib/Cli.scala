// Command-line parsing with decline.
package nixosconf.lib

import cats.data.EitherT
import cats.effect.{ExitCode, IO}
import cats.effect.cps.*
import cats.effect.std.Console
import com.monovore.decline.Command

object Cli:

  /** Parses `args` with `command`. `--help` prints the help and ends the script
    * (exit 0); a parse error prints it with the errors to stderr (exit 1).
    */
  def parse[A](command: Command[A], args: List[String]): ScriptT[A] = async[ScriptT] {
    command.parse(args, IO(sys.env).awaitIO) match
      case Right(a) => a
      case Left(help) if help.errors.isEmpty =>
        Console[IO].println(help).awaitIO
        EitherT.leftT[IO, A](Exit(ExitCode.Success): ScriptError).await
      case Left(help) =>
        Console[IO].errorln(help).awaitIO
        EitherT.leftT[IO, A](Exit(ExitCode.Error): ScriptError).await
  }
