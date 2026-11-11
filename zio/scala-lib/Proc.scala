// Running commands (zio-process).
package nixosconf.lib

import sun.misc.Signal
import zio.*
import zio.nio.file.Path
import zio.process.{Command, CommandError, ProcessInput, ProcessOutput, Process as ZProcess}

/** A command line: a program and its arguments, never through a shell. */
final case class Cmd(args: Seq[String]):
  def ++(more: IterableOnce[String]): Cmd = Cmd(args ++ more)
  /** For messages. */
  def describe: String = args.mkString(" ")

object Cmd:
  def apply(program: String, args: String*): Cmd = new Cmd(program +: args)

/** Where a command's stdout or stderr goes. */
enum Out:
  case Inherit
  case Discard
  case ToFile(path: Path)

object Proc:

  /** Runs `cmd`, its output going where `stdout`/`stderr` say (the terminal by
    * default), and returns its exit code. Interrupting (e.g. Ctrl-C) kills it.
    */
  def status(
      cmd: Cmd,
      cwd: Path,
      stdout: Out = Out.Inherit,
      stderr: Out = Out.Inherit,
      env: Map[String, String] = Map.empty,
      stdin: Option[String] = None
  ): IO[ScriptError, Int] =
    ZIO.scoped(
      spawned(command(cmd, cwd, env, stdin).stdout(output(stdout)).stderr(output(stderr)), cmd)
        .flatMap(_.exitCode.orDie)
        .map(_.code)
    )

  /** Runs `cmd` with `stdin` as its input, returning its exit code and its output
    * the way bash's `$(...)` has it (trailing newlines dropped, unless `raw`). Its
    * stderr goes to the terminal, or into the output with `mergeErr` (bash's `2>&1`).
    */
  def output(
      cmd: Cmd,
      cwd: Path,
      stdin: Option[String] = None,
      mergeErr: Boolean = false,
      env: Map[String, String] = Map.empty,
      raw: Boolean = false
  ): IO[ScriptError, (Int, String)] =
    val piped = command(cmd, cwd, env, stdin).stdout(ProcessOutput.Pipe)
    ZIO.scoped(
      for
        process <- spawned(
          if mergeErr then piped.redirectErrorStream(true) else piped.stderr(ProcessOutput.Inherit),
          cmd
        )
        // Until the command closes it (it ends); stderr isn't piped (or is merged),
        // so it can't fill up meanwhile.
        out <- process.stdout.string.orDie
        code <- process.exitCode.orDie
      yield (code.code, if raw then out else out.reverse.dropWhile(_ == '\n').reverse)
    )

  /** As [[output]], failing with `error` (given the output) unless it exits 0. */
  def outputOr(
      cmd: Cmd,
      cwd: Path,
      stdin: Option[String] = None,
      mergeErr: Boolean = false
  )(error: String => String): IO[ScriptError, String] =
    output(cmd, cwd, stdin, mergeErr).flatMap {
      case (0, out) => ZIO.succeed(out)
      case (_, out) => fail(error(out))
    }

  /** As [[status]], failing unless it exits 0. */
  def succeed(cmd: Cmd, cwd: Path): IO[ScriptError, Unit] =
    succeedOr(cmd, cwd)(code => show"`${cmd.describe}` exited with $code")

  /** As [[status]], failing with `error` (given the exit code) unless it exits 0. */
  def succeedOr(cmd: Cmd, cwd: Path, env: Map[String, String] = Map.empty)(
      error: Int => String
  ): IO[ScriptError, Unit] =
    status(cmd, cwd, env = env).flatMap(code => failUnless(code == 0)(error(code)))

  /** As [[status]], for an interactive program, the way bash's `exec` would hand
    * it the terminal: Ctrl-C is the program's (e.g. a Python shell's
    * KeyboardInterrupt), so it doesn't end this script and leave the program
    * behind; it's back to normal once the program exits.
    */
  def foreground(cmd: Cmd, cwd: Path): IO[ScriptError, Int] =
    ZIO.acquireReleaseWith(ZIO.succeed(Signal.handle(Signal("INT"), _ => ())))(previous =>
      ZIO.succeed(Signal.handle(Signal("INT"), previous))
    )(_ => status(cmd, cwd))

  /** `cmd` in `cwd`, with `env` added to the environment and `stdin` as its input
    * (else the terminal's).
    */
  private def command(cmd: Cmd, cwd: Path, env: Map[String, String], stdin: Option[String]): Command =
    val base = Command(cmd.args.head, cmd.args.tail*).workingDirectory(cwd.toFile).env(env)
    stdin.fold(base.stdin(ProcessInput.Inherit))(input => base.stdin(ProcessInput.fromUTF8String(input)))

  private def output(out: Out): ProcessOutput = out match
    case Out.Inherit      => ProcessOutput.Inherit
    case Out.Discard      => ProcessOutput.FileRedirect(java.io.File("/dev/null"))
    case Out.ToFile(path) => ProcessOutput.FileRedirect(path.toFile)

  /** Started, and killed when the scope closes (e.g. when interrupted, by Ctrl-C).
    * A command that can't be started is a [[Fail]].
    */
  private def spawned(command: Command, cmd: Cmd): ZIO[Scope, ScriptError, ZProcess] =
    ZIO.acquireRelease(
      command.run.mapError {
        case CommandError.WorkingDirectoryMissing(dir) =>
          Fail(show"could not run `${cmd.describe}`: no directory ${dir.toString}")
        case e: CommandError.NotStarted => Fail(show"could not run `${cmd.describe}`: ${e.getCause.getMessage}")
        case e                          => Fail(show"could not run `${cmd.describe}`: ${e.getCause: Throwable}")
      }
    )(process => process.execute(p => if p.isAlive then p.destroy()).ignore)
