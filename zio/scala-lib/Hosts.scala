// The machines this repository configures: hosts/<name>/.
package nixosconf.lib

import yantl.Newtype
import zio.*
import zio.cli.{Args, HelpDoc}
import zio.nio.file.{Files, Path}

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
final case class KnownHosts(all: NonEmptyChunk[HostName]):
  def names: String = all.map(_.name).mkString(" ")

  /** A host's name as a positional argument: an unknown one is a usage error. */
  val argument: Args[HostName] =
    (Args.text("name") ?? "The host: one of hosts/<name>/.")
      .mapOrFail(name => all.find(_.name == name).toRight(HelpDoc.p(show"No host '$name' (there are: $names).")))

object KnownHosts:
  def discover(root: Path): IO[ScriptError, KnownHosts] =
    for
      dirs <- Files.list(root / "hosts").filterZIO(Files.isDirectory(_)).runCollect.orDie
      hosts <- ZIO
        .foreach(dirs.map(_.filename.toString).sorted)(name =>
          ZIO.fromEither(AttrName.make.asString(name)).map(HostName(_))
        )
        .flatMap(hosts => ZIO.fromOption(NonEmptyChunk.fromChunk(hosts)).orElseFail("no hosts in hosts/"))
        .mapError(Fail(_))
    yield KnownHosts(hosts)
