// deploy.scala's arguments, which upgrade.scala takes too (and hands on to it).
package nixosconf.lib

import kyo.*

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
  def parse(name: String)(using Frame): Action < Abort[Stop] =
    Abort.get(
      values
        .find(_.name == name)
        .toRight(Fail(show"Unknown action '$name' (expected one of: ${values.map(_.name).mkString(", ")})."))
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
    target: Maybe[String],
    update: Boolean,
    buildOn: BuildOn
):
  /** Where the machine is reached: TARGET, or arturas@<name>.lan. */
  def destination: String = target.getOrElse(show"arturas@${host.name}.lan")

object DeployArgs:
  /** What --help says about the arguments (case-app parses only options; these are
    * positional, or environment variables).
    */
  val help: String =
    """Arguments: <name> [switch|boot|test|dry-activate|build] (default: switch).
      |Environment variables:
      |  TARGET=user@host  Where the machine is reached (default: arturas@<name>.lan).
      |  UPDATE=1          Update the flake inputs (flake.lock) first; commit it afterwards.
      |  BUILD_LOCAL=1     Build on this machine and copy the result over, instead of building
      |                    on the target (worth it only when something has to be compiled;
      |                    needs the target to trust this user, else it builds there).""".stripMargin

  /** An environment variable that is 1 or 0 (or unset: false). */
  private def flag(name: String)(using Frame): Boolean < (Sync & Abort[Stop]) =
    env(name).map {
      case Absent | Present("0") | Present("") => false
      case Present("1")                        => true
      case Present(other)                      => usage(show"$name must be 1 or 0, not '$other'.")
    }

  def parse(known: KnownHosts, args: Chunk[String])(using Frame): DeployArgs < (Sync & Abort[Stop]) =
    direct {
      val (name, action) = args.toList match
        case List(name)         => (name, Action.Switch)
        case List(name, action) => (name, Action.parse(action).now)
        case _                  => usage("expected <name> [action]").now
      DeployArgs(
        known.parse(name).now,
        action,
        env("TARGET").now,
        flag("UPDATE").now,
        if flag("BUILD_LOCAL").now then BuildOn.Local else BuildOn.Target
      )
    }

/** deploy.scala's (and upgrade.scala's) options: none but --help; see
  * [[DeployArgs.help]].
  */
final case class DeployOptions()
