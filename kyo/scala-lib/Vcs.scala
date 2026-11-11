// Asking version control (jj, or git without it) about the working copy.
package nixosconf.lib

import kyo.*

object Vcs:

  /** Which of `paths` are new or different from the last commit (for jj: changed in
    * the working-copy commit), one per line; "" when none are.
    */
  def changed(root: Path, paths: Seq[String])(using Frame): String < (Proc.Effects & PathRead) =
    direct {
      val command =
        if (root / ".jj").isDirectory.now then Command(Seq("jj", "diff", "--name-only", "--") ++ paths*)
        else Command(Seq("git", "status", "--porcelain", "--") ++ paths*)
      Proc.outputOr(command, root)(_ => show"`${Proc.describe(command)}` failed").now
    }
