// The compile error behind Renders.scala's catch-all.
package nixosconf.lib

import kyo.Render
import scala.quoted.*

object RenderMacro:
  def none[A: Type](using q: Quotes): Expr[Render[A]] =
    q.reflect.report.errorAndAbort(
      s"no Render[${Type.show[A]}]: render\"...\" takes only what scala-lib/Renders.scala " +
        "gives a Render (render a field or a String instead, or add one there)"
    )
