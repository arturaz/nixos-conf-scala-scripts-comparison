// deploy.scala's arguments, which upgrade.scala takes too (and hands on to it).
package nixosconf.lib

import cats.syntax.all.*
import com.monovore.decline.{Argument, Opts}

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
  given Argument[Action] = Argument.from("action")(name =>
    values
      .find(_.name == name)
      .toValidNel(show"Unknown action '$name' (expected one of: ${values.map(_.name).mkString(", ")}).")
  )

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
  /** An environment variable that is 1 or 0 (or unset: false). */
  private def flag(name: String, help: String): Opts[Boolean] =
    Opts
      .env[String](name, help, "1|0")
      .mapValidated {
        case "1"      => true.validNel
        case "0" | "" => false.validNel
        case other    => show"$name must be 1 or 0, not '$other'.".invalidNel
      }
      .withDefault(false)

  def opts(known: KnownHosts): Opts[DeployArgs] =
    import known.given
    (
      Opts.argument[HostName]("name"),
      Opts
        .argument[Action](Action.values.map(_.name).mkString("|"))
        .withDefault(Action.Switch),
      Opts.env[String]("TARGET", "Where the machine is reached (default: arturas@<name>.lan).").orNone,
      flag("UPDATE", "1: update the flake inputs (flake.lock) first; commit it afterwards."),
      flag(
        "BUILD_LOCAL",
        "1: build on this machine and copy the result over, instead of building on the " +
          "target (worth it only when something has to be compiled; needs the target to " +
          "trust this user, else it builds there)."
      ).map(if _ then BuildOn.Local else BuildOn.Target)
    ).mapN(DeployArgs.apply)
