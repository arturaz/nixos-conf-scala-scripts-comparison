// Running commands (kyo-system's Command).
package nixosconf.lib

import java.nio.charset.StandardCharsets
import kyo.*
import sun.misc.Signal

/** Where a command's stdout or stderr goes. */
enum Out:
  case Inherit
  case Discard
  case ToFile(path: Path)

object Proc:

  /** What running a command needs: waiting for it, and failing (with a message) when
    * it can't be started.
    */
  type Effects = Async & Abort[Stop]

  /** The command line, for messages. */
  def describe(command: Command): String = command.args.mkString(" ")

  /** `command` in `cwd`, with `env` added to the environment and `stdin` as its input
    * (else the terminal's).
    */
  private def configured(command: Command, cwd: Path, env: Map[String, String], stdin: Maybe[String]): Command =
    val base = command.cwd(cwd).envAppend(env)
    stdin.fold(base.inheritStdin)(base.stdin(_))

  private def sending(command: Command, stdout: Out, stderr: Out)(using Frame): Command =
    val out = stdout match
      case Out.Inherit      => command.inheritStdout
      case Out.Discard      => command.stdoutToFile(Path("/dev/null"))
      case Out.ToFile(path) => command.stdoutToFile(path)
    stderr match
      case Out.Inherit      => out.inheritStderr
      case Out.Discard      => out.stderrToFile(Path("/dev/null"))
      case Out.ToFile(path) => out.stderrToFile(path)

  /** Started, and killed when the scope closes (e.g. when interrupted, by Ctrl-C). */
  private def spawned(command: Command)(using Frame): Process < (Sync & Scope & Abort[Stop]) =
    Abort.recover[CommandException](e => fail(show"could not run `${describe(command)}`: ${e.getMessage}"))(
      command.spawn
    )

  /** Runs `command`, its output going where `stdout`/`stderr` say (the terminal by
    * default), and returns its exit code. Interrupting (e.g. Ctrl-C) kills it.
    */
  def status(
      command: Command,
      cwd: Path,
      stdout: Out = Out.Inherit,
      stderr: Out = Out.Inherit,
      env: Map[String, String] = Map.empty,
      stdin: Maybe[String] = Absent
  )(using Frame): Int < Effects =
    Scope.run(direct {
      val process = spawned(sending(configured(command, cwd, env, stdin), stdout, stderr)).now
      process.waitFor.now.toInt
    })

  /** Runs `command` with `stdin` as its input, returning its exit code and its output
    * the way bash's `$(...)` has it (trailing newlines dropped, unless `raw`). Its
    * stderr goes to the terminal, or into the output with `mergeErr` (bash's `2>&1`).
    */
  def output(
      command: Command,
      cwd: Path,
      stdin: Maybe[String] = Absent,
      mergeErr: Boolean = false,
      env: Map[String, String] = Map.empty,
      raw: Boolean = false
  )(using Frame): (Int, String) < Effects =
    val piped = configured(command, cwd, env, stdin)
    Scope.run(direct {
      val process = spawned(if mergeErr then piped.redirectErrorStream(true) else piped.inheritStderr).now
      // Read in a fiber of its own while the command runs (it would block on a full
      // pipe otherwise). Unscoped: when the command is killed its output ends, and so
      // does the read; waiting for the read before killing it could wait for good.
      val reader = Fiber.initUnscoped(Scope.run(process.stdout.run)).now
      val code = process.waitFor.now
      val out = String(reader.get.now.toArray, StandardCharsets.UTF_8)
      (code.toInt, if raw then out else out.reverse.dropWhile(_ == '\n').reverse)
    })

  /** As [[output]], failing with `error` (given the output) unless it exits 0. */
  def outputOr(
      command: Command,
      cwd: Path,
      stdin: Maybe[String] = Absent,
      mergeErr: Boolean = false
  )(error: String => String)(using Frame): String < Effects =
    output(command, cwd, stdin, mergeErr).map {
      case (0, out) => out
      case (_, out) => fail(error(out))
    }

  /** As [[status]], failing unless it exits 0. */
  def succeed(command: Command, cwd: Path)(using Frame): Unit < Effects =
    succeedOr(command, cwd)(code => show"`${describe(command)}` exited with $code")

  /** As [[status]], failing with `error` (given the exit code) unless it exits 0. */
  def succeedOr(command: Command, cwd: Path, env: Map[String, String] = Map.empty)(
      error: Int => String
  )(using Frame): Unit < Effects =
    status(command, cwd, env = env).map(code => unless(code == 0)(fail(error(code))))

  /** As [[status]], for an interactive program, the way bash's `exec` would hand
    * it the terminal: Ctrl-C is the program's (e.g. a Python shell's
    * KeyboardInterrupt), so it doesn't end this script and leave the program
    * behind; it's back to normal once the program exits.
    */
  def foreground(command: Command, cwd: Path)(using Frame): Int < Effects =
    Scope.run(
      Scope
        .acquireRelease(Signal.handle(Signal("INT"), _ => ()))(previous =>
          Sync.defer(Signal.handle(Signal("INT"), previous)).unit
        )
        .andThen(status(command, cwd))
    )
