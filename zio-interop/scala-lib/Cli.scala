// Command-line parsing with decline.
package nixosconf.lib

import com.monovore.decline.Command
import zio.{ExitCode, IO, ZIO}

object Cli:

  /** Parses `args` with `command`. `--help` prints the help and ends the script
    * (exit 0); a parse error prints it with the errors to stderr (exit 1).
    */
  def parse[A](command: Command[A], args: List[String]): IO[ScriptError, A] =
    zio.System.envs.orDie.flatMap { env =>
      command.parse(args, env) match
        case Right(a)                          => ZIO.succeed(a)
        case Left(help) if help.errors.isEmpty => printOut(help.toString) *> ZIO.fail(Exit(ExitCode.success))
        case Left(help)                        => printErr(help.toString) *> ZIO.fail(Exit(ExitCode.failure))
    }
