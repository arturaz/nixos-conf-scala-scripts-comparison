// yantl validator rules yantl itself lacks.
package nixosconf.lib

import cats.syntax.all.*
import scala.util.matching.Regex
import yantl.ValidatorRule

object Rules:

  /** The value didn't match `pattern`; `expected` says what it should look like. */
  final case class DidNotMatch(value: String, expected: String) derives CanEqual:
    override def toString: String = show"'$value' isn't $expected"

  /** The whole string must match `pattern`. */
  def matches(pattern: Regex, expected: String): ValidatorRule[String, DidNotMatch] =
    ValidatorRule.of(s => Option.unless(pattern.matches(s))(DidNotMatch(s, expected)))
