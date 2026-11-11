// Asking version control (jj, or git without it) about the working copy.
package nixosconf.lib

import zio.*
import zio.nio.file.{Files, Path}

object Vcs:

  /** Which of `paths` are new or different from the last commit (for jj: changed in
    * the working-copy commit), one per line; "" when none are.
    */
  def changed(root: Path, paths: Seq[String]): IO[ScriptError, String] =
    Files.isDirectory(root / ".jj").flatMap { jj =>
      val cmd =
        if jj then Cmd("jj", "diff", "--name-only", "--") ++ paths
        else Cmd("git", "status", "--porcelain", "--") ++ paths
      Proc.outputOr(cmd, root)(_ => show"`${cmd.describe}` failed")
    }
