// The machines this repository configures: hosts/<name>/.
package nixosconf.lib

import kyo.*
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

/** The hosts there are, in hosts/ (at least one). */
final case class KnownHosts private (all: Chunk[HostName]):
  def names: String = all.map(_.name).mkString(" ")

  /** The host named `name`, or a usage error. */
  def parse(name: String)(using Frame): HostName < Abort[Stop] =
    Abort.get(all.find(_.name == name).toRight(Fail(show"No host '$name' (there are: $names). (see --help)")))

object KnownHosts:
  def discover(root: Path)(using Frame): KnownHosts < (Abort[Stop] & PathRead) =
    direct {
      val dirs = (root / "hosts").list.now.filter(_.isDirectory.now)
      val names = dirs.flatMap(_.name.toOption).sorted
      val hosts = Chunk.from(names.map(n => AttrName.make.asString(n).map(HostName(_)).orFail.now))
      if hosts.isEmpty then fail("no hosts in hosts/").now else KnownHosts(hosts)
    }
