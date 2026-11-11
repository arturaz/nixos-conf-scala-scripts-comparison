// Running commands.
package nixosconf.lib

import cats.syntax.all.*
import sun.misc.Signal

object Proc:

  /** Runs `proc`, its output going where `stdout`/`stderr` say (the terminal by
    * default), and returns its exit code. Interrupting (e.g. Ctrl-C) kills it.
    */
  def status(
      proc: os.proc,
      cwd: os.Path,
      stdout: os.ProcessOutput = os.Inherit,
      stderr: os.ProcessOutput = os.Inherit,
      env: Map[String, String] = Map.empty,
      stdin: os.ProcessInput = os.Inherit
  ): Int =
    spawned(proc, cwd, stdin, stdout, stderr, env) { p =>
      p.waitFor(): Unit
      p.exitCode()
    }

  /** Runs `proc` with `stdin` as its input, returning its exit code and its output
    * the way bash's `$(...)` has it (trailing newlines dropped, unless `raw`). Its
    * stderr goes to the terminal, or into the output with `mergeErr` (bash's `2>&1`).
    */
  def output(
      proc: os.proc,
      cwd: os.Path,
      stdin: os.ProcessInput = os.Inherit,
      mergeErr: Boolean = false,
      env: Map[String, String] = Map.empty,
      raw: Boolean = false
  ): (Int, String) =
    spawned(proc, cwd, stdin, os.Pipe, if mergeErr then os.Pipe else os.Inherit, env, mergeErr) { p =>
      val out = String(p.stdout.readAllBytes(), "UTF-8")
      p.waitFor(): Unit
      (p.exitCode(), if raw then out else out.reverse.dropWhile(_ == '\n').reverse)
    }

  /** As [[output]], failing with `error` (given the output) unless it exits 0. */
  def outputOr(
      proc: os.proc,
      cwd: os.Path,
      stdin: os.ProcessInput = os.Inherit,
      mergeErr: Boolean = false
  )(error: String => String): Either[ScriptError, String] =
    output(proc, cwd, stdin, mergeErr) match
      case (0, out) => Right(out)
      case (_, out) => fail(error(out))

  /** `use` on the started process, which is killed afterwards if it's still running
    * (`use` interrupted, e.g. by Ctrl-C: `waitFor` throws InterruptedException).
    * Without os-lib's own JVM shutdown hook (`destroyOnExit`): spawning from a
    * finalizer during shutdown (deploy's `ssh -O exit` on Ctrl-C) would fail
    * registering it ("Shutdown in progress").
    */
  private def spawned[A](
      proc: os.proc,
      cwd: os.Path,
      stdin: os.ProcessInput,
      stdout: os.ProcessOutput,
      stderr: os.ProcessOutput,
      env: Map[String, String],
      mergeErr: Boolean = false
  )(use: os.SubProcess => A): A =
    val p = proc.spawn(
      cwd = cwd,
      env = env,
      stdin = stdin,
      stdout = stdout,
      stderr = stderr,
      mergeErrIntoOut = mergeErr,
      destroyOnExit = false
    )
    try use(p)
    finally if p.isAlive() then p.destroy()

  /** As [[status]], failing unless it exits 0. */
  def succeed(proc: os.proc, cwd: os.Path): Either[ScriptError, Unit] =
    succeedOr(proc, cwd)(code => show"`${proc.commandChunks.mkString(" ")}` exited with $code")

  /** As [[status]], failing with `error` (given the exit code) unless it exits 0. */
  def succeedOr(proc: os.proc, cwd: os.Path, env: Map[String, String] = Map.empty)(
      error: Int => String
  ): Either[ScriptError, Unit] =
    val code = status(proc, cwd, env = env)
    failUnless(code == 0)(error(code))

  /** As [[status]], for an interactive program, the way bash's `exec` would hand
    * it the terminal: Ctrl-C is the program's (e.g. a Python shell's
    * KeyboardInterrupt), so it doesn't end this script and leave the program
    * behind; it's back to normal once the program exits.
    */
  def foreground(proc: os.proc, cwd: os.Path): Int =
    val previous = Signal.handle(Signal("INT"), _ => ())
    try status(proc, cwd)
    finally Signal.handle(Signal("INT"), previous): Unit

  /** Discards a command's output. */
  val devNull: os.ProcessOutput = os.Path("/dev/null")
