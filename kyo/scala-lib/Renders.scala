// The `show"..."` interpolator, and kyo's Render for what the scripts put into it:
// they use it, not `s"..."`, so that only what has a Render here (a deliberate
// rendering) can go into one. kyo's own catch-all (any value's toString, in Render's
// companion) is shadowed by `noRender`: being in lexical scope (scripts import
// nixosconf.lib.given) it's found first, and fails the compilation. The instances
// here, more specific than it, win over it.
package nixosconf.lib

import kyo.*

given Render[String] = Render.from(identity)
given Render[Int] = Render.from(_.toString)
given Render[Long] = Render.from(_.toString)
given Render[Unit] = Render.from(_ => "()")
/** As the OS has it (`Path.toString` is its `show`). */
given Render[Path] = Render.from(_.toString)

inline given noRender[A]: Render[A] = ${ RenderMacro.none[A] }

extension (sc: StringContext)
  /** kyo's `render"..."`, but processing escapes (`\n`, `\\`) in every literal part, as
    * `s"..."` does: kyo's leaves the first part's as written.
    */
  def show(args: Render.Rendered*): String = sc.s(args*)
