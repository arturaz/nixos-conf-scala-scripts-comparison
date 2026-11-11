// deploy.scala's arguments, which upgrade.scala takes too (and hands on to it).
package nixosconf.lib

import zio.*
import zio.cli.{Args, Command, HelpDoc}

/** What deploy.scala does with the new system once it's built. */
enum Action(val name: String):
  /** Activates it now and boots into it. */
  case Switch extends Action("switch")
  /** Boots into it next time. */
  case Boot extends Action("boot")
  /** Activates it now, but doesn't boot into it. */
  case Test extends Action("test")
  /** Says what activating it would do. */
  case DryActivate extends Action("dry-activate")
  /** Only builds it (on the target). */
  case Build extends Action("build")

object Action:
  /** An optional positional argument, switch when not given. */
  val argument: Args[Action] =
    (Args.text(values.map(_.name).mkString("|")) ?? "What to do with the new system (default: switch).")
      .atMost(1)
      .mapOrFail {
        case Nil => Right(Switch)
        case name :: _ =>
          values
            .find(_.name == name)
            .toRight(
              HelpDoc.p(show"Unknown action '$name' (expected one of: ${values.map(_.name).mkString(", ")}).")
            )
      }

/** Where it builds. */
enum BuildOn:
  /** The target: it downloads from cache.nixos.org what it can, and already has most
    * of the rest.
    */
  case Target
  /** This machine, copying the result over: worth it only when something has to be
    * compiled. Needs the target to trust our ssh user (else it falls back to Target).
    */
  case Local

final case class DeployArgs(
    host: HostName,
    action: Action,
    target: Option[String],
    update: Boolean,
    buildOn: BuildOn
):
  /** Where the machine is reached: TARGET, or arturas@<name>.lan. */
  def destination: String = target.getOrElse(show"arturas@${host.name}.lan")

object DeployArgs:
  /** What --help says about the environment variables (zio-cli reads none). */
  val footer: HelpDoc =
    HelpDoc.h1("environment variables") + HelpDoc.descriptionList(
      HelpDoc.Span.text("TARGET") -> HelpDoc.p("Where the machine is reached (default: arturas@<name>.lan)."),
      HelpDoc.Span.text("UPDATE") -> HelpDoc.p("1: update the flake inputs (flake.lock) first; commit it afterwards."),
      HelpDoc.Span.text("BUILD_LOCAL") -> HelpDoc.p(
        "1: build on this machine and copy the result over, instead of building on the target " +
          "(worth it only when something has to be compiled; needs the target to trust this " +
          "user, else it builds there)."
      )
    )

  /** An environment variable that is 1 or 0 (or unset: false). */
  private def flag(name: String): IO[ScriptError, Boolean] =
    env(name).flatMap {
      case None | Some("0") | Some("") => ZIO.succeed(false)
      case Some("1")                   => ZIO.succeed(true)
      case Some(other)                 => fail(show"$name must be 1 or 0, not '$other' (see --help).")
    }

  /** The arguments (`<name> [action]`) and the environment variables. */
  def parse(known: KnownHosts, name: String, help: String, args: List[String]): IO[ScriptError, DeployArgs] =
    for
      (host, action) <- Cli.parse(Command(name, known.argument ++ Action.argument).withHelp(help), args, footer)
      target <- env("TARGET")
      update <- flag("UPDATE")
      local <- flag("BUILD_LOCAL")
    yield DeployArgs(host, action, target, update, if local then BuildOn.Local else BuildOn.Target)
