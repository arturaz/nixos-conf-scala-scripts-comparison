// cats' Show for the types the scripts put into messages: they use the `show"..."`
// interpolator, not `s"..."`, so only what has a Show (a deliberate rendering) can go
// into one.
package nixosconf.lib

import cats.Show

given Show[os.Path] = Show.fromToString
given Show[os.RelPath] = Show.fromToString
given Show[os.SubPath] = Show.fromToString
/** Its message, as `toString` has it (`<class>: <message>`). */
given Show[Throwable] = Show.fromToString
