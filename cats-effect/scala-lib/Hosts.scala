// The machines this repository configures: hosts/<name>/.
package nixosconf.lib

import cats.data.NonEmptyVector
import cats.effect.IO
import cats.syntax.all.*
import com.monovore.decline.Argument
import yantl.Newtype

/** A host's name: hosts/<name>/, the flake's `nixosConfigurations.<name>`. Only
  * [[KnownHosts]] makes one, so it always names a host that exists.
  */
object HostName extends Newtype.WithoutValidationOf[AttrName]:
  extension (host: HostName)
    def name: String = host.unwrap.unwrap

    /** `nixosConfigurations.<name>.config`. */
    def config(flake: FlakeRef): Installable =
      Installable(
        flake,
        AttrPath.of(AttrName.make.orThrow("nixosConfigurations"), host.unwrap, AttrName.make.orThrow("config"))
      )
type HostName = HostName.Type

/** The hosts there are, in hosts/. */
final case class KnownHosts(all: NonEmptyVector[HostName]):
  def names: String = all.map(_.name).mkString_(" ")

  given Argument[HostName] = Argument.from("name")(name =>
    all.find(_.name == name).toValidNel(show"No host '$name' (there are: $names).")
  )

object KnownHosts:
  def discover(root: os.Path): IO[Either[ScriptError, KnownHosts]] =
    IO.blocking(os.list(root / "hosts").filter(os.isDir(_)))
      .map(
        _.map(_.last).sorted.toVector
          .traverse(AttrName.make.asString(_).map(HostName(_)))
          .flatMap(NonEmptyVector.fromVector(_).toRight("no hosts in hosts/"))
          .map(KnownHosts(_))
          .orFail
      )
