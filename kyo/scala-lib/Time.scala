// Printing how long things took.
package nixosconf.lib

import kyo.*

object Time:

  /** 75 s -> "1m 15s", 42 s -> "42s" (whole seconds, rounded down). */
  def format(d: Duration): String =
    val s = d.toSeconds
    if s >= 60 then show"${s / 60}m ${s % 60}s" else show"${s}s"

/** How long `effect` took, by the monotonic clock. */
object Timed:
  def apply[A, S](effect: => A < S)(using Frame): (Duration, A) < (S & Sync) =
    direct {
      val watch = Clock.stopwatch.now
      val a = effect.now
      (watch.elapsed.now, a)
    }
