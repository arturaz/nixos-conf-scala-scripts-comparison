// The `show"..."` interpolator and its Show type class (ZIO has none; cats' was used
// before): the scripts use it, not `s"..."`, so only what has a Show here (a
// deliberate rendering) can go into a message. Anything else doesn't compile.
package nixosconf.lib

import scala.language.implicitConversions

/** How a value goes into a `show"..."` message. */
trait Show[-A]:
  def show(a: A): String

object Show:
  def fromToString[A]: Show[A] = _.toString

  given Show[String] = identity(_)
  given Show[Int] = fromToString
  given Show[Long] = fromToString
  given Show[Char] = fromToString
  /** As the OS has it. */
  given Show[zio.nio.file.Path] = fromToString
  /** Its message, as `toString` has it (`<class>: <message>`). */
  given Show[Throwable] = fromToString

/** A value rendered by its [[Show]]: what `show"..."` takes. */
opaque type Shown = String

object Shown:
  /** A Scala 2-style implicit conversion, as cats' `show"..."` has it: a `given
    * Conversion` would need `import scala.language.implicitConversions` wherever it's
    * used, this only here.
    */
  implicit def fromShow[A](a: A)(using s: Show[A]): Shown = s.show(a)

extension (sc: StringContext)
  /** As `s"..."`, but only for values with a [[Show]]. */
  def show(args: Shown*): String = sc.s(args*)
