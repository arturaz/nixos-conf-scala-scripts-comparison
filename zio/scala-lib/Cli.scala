// Command-line parsing with zio-cli.
package nixosconf.lib

import zio.*
import zio.cli.{BuiltInOption, CliConfig, Command, CommandDirective, HelpDoc}

object Cli:

  /** Case-sensitive (zio-cli's default isn't); typos in option names are still
    * corrected in its error messages.
    */
  val config: CliConfig = CliConfig.default.copy(caseSensitive = true)

  /** Parses `args` with `command` (one made by zio-cli's `Command(name, ...)`).
    * `--help` prints the usage, the command's help and `footer` (e.g. the environment
    * variables it reads; zio-cli has no environment options) and ends the script
    * (exit 0); a parse error prints why to stderr (exit 1). zio-cli's other built-in
    * options (`--wizard`, shell completion) are refused.
    *
    * zio-cli's own runner (CliApp) isn't used: it prints a FIGlet banner of the name
    * with the help, parse errors to stdout, and ignores arguments left over.
    */
  def parse[A](command: Command[A], args: List[String], footer: HelpDoc = HelpDoc.empty): IO[ScriptError, A] =
    command
      .parse(command.names.toList ++ args, config)
      .foldZIO(
        error =>
          printErr(error.error.toPlaintext(80, color = false).trim) *>
            printErr("(see --help)") *>
            ZIO.fail(Halt(ExitCode.failure)),
        {
          case CommandDirective.UserDefined(Nil, value) => ZIO.succeed(value)
          case CommandDirective.UserDefined(leftover, _) =>
            ZIO.fail(Fail(show"unexpected arguments: ${leftover.mkString(" ")} (see --help)"))
          case CommandDirective.BuiltIn(BuiltInOption.ShowHelp(synopsis, help)) =>
            val usage = HelpDoc.h1("usage") +
              HelpDoc.blocks(synopsis.enumerate(config).map(span => HelpDoc.p(HelpDoc.Span.text("$ ") + span)))
            printOut((usage + withoutFiller(help) + footer).toPlaintext(80, color = false).trim) *>
              ZIO.fail(Halt(ExitCode.success))
          case CommandDirective.BuiltIn(_) => ZIO.fail(Fail("only --help is supported of zio-cli's built-in options"))
        }
      )

  /** What zio-cli says of every text argument and flag, with no way to turn it off. */
  private val filler = Set("A user-defined piece of text.", "A true or false value.")

  /** `doc` without zio-cli's [[filler]] paragraphs. */
  private def withoutFiller(doc: HelpDoc): HelpDoc = doc match
    case HelpDoc.Paragraph(span) if filler(span.text) => HelpDoc.empty
    case HelpDoc.Sequence(left, right)                 => withoutFiller(left) + withoutFiller(right)
    case HelpDoc.DescriptionList(definitions) =>
      HelpDoc.DescriptionList(definitions.map((term, help) => (term, withoutFiller(help))))
    case HelpDoc.Enumeration(elements) => HelpDoc.Enumeration(elements.map(withoutFiller))
    case other                         => other
