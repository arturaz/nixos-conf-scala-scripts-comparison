// Printing how long things took (measure with `timed`, in Script.scala).
package nixosconf.lib

import cats.syntax.all.*
import scala.concurrent.duration.FiniteDuration

object Time:

  /** 75 s -> "1m 15s", 42 s -> "42s" (whole seconds, rounded down). */
  def format(d: FiniteDuration): String =
    val s = d.toSeconds
    if s >= 60 then show"${s / 60}m ${s % 60}s" else show"${s}s"
