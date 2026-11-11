// Command-line parsing with decline.
package nixosconf.lib

import com.monovore.decline.Command
import ox.ExitCode

object Cli:

  /** Parses `args` with `command`. `--help` prints the help and ends the script
    * (exit 0); a parse error prints it with the errors to stderr (exit 1).
    */
  def parse[A](command: Command[A], args: List[String]): Either[ScriptError, A] =
    command.parse(args, sys.env) match
      case Right(a) => Right(a)
      case Left(help) if help.errors.isEmpty =>
        println(help)
        Left(Exit(ExitCode.Success))
      case Left(help) =>
        Console.err.println(help)
        Left(Exit(ExitCode.Failure()))
