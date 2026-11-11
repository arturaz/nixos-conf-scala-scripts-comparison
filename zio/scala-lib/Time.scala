// Printing how long things took (measure with ZIO's `.timed`).
package nixosconf.lib

import zio.Duration

object Time:

  /** 75 s -> "1m 15s", 42 s -> "42s" (whole seconds, rounded down). */
  def format(d: Duration): String =
    val s = d.toSeconds
    if s >= 60 then show"${s / 60}m ${s % 60}s" else show"${s}s"
