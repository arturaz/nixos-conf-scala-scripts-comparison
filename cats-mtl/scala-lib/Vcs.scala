// Asking version control (jj, or git without it) about the working copy.
package nixosconf.lib

import cats.syntax.all.*
import cats.effect.IO

object Vcs:

  /** Which of `paths` are new or different from the last commit (for jj: changed in
    * the working-copy commit), one per line; "" when none are.
    */
  def changed(root: os.Path, paths: Seq[String])(using CanFail): IO[String] =
    IO.blocking(os.isDir(root / ".jj")).flatMap { jj =>
      val proc =
        if jj then os.proc("jj", "diff", "--name-only", "--", paths)
        else os.proc("git", "status", "--porcelain", "--", paths)
      Proc.outputOr(proc, root)(_ => show"`${proc.commandChunks.mkString(" ")}` failed")
    }
