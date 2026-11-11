#!/usr/bin/env bash
//usr/bin/env true; exec "$(dirname "$0")/scala-lib/run" "$0" "$@" # bash runs this line; Scala sees a comment
// Runs the VM tests (tests/checks/*.nix, the flake's checks): all of them, or the ones
// named, all at once (or one after the other, -s), then prints how each went and how
// long it took.
// They boot throwaway VMs on this computer (needs KVM; the `dmz` one boots VMs inside a
// VM, so it wants nested KVM: /sys/module/kvm_amd/parameters/nested (or kvm_intel)
// should say 1 or Y). Nothing is deployed. upgrade.scala runs this before deploying.
// The VMs' consoles and the script's progress are printed as they go (-L). A failed
// test doesn't stop the others; the exit code is 1 if any failed.
// Usage: --help.
//> using file scala-lib

import cats.data.NonEmptyVector
import cats.syntax.all.*
import com.monovore.decline.{Argument, Command, Opts}
import nixosconf.lib.{*, given}
import yantl.Newtype
import zio.{Duration, ExitCode, IO, UIO, ZIO}
import zio.interop.catz.*

/** A test's name: tests/checks/<name>.nix, which is the flake's
  * `checks.<system>.<name>`. Only [[KnownTests]] makes one, so it always names a
  * test that exists.
  */
object TestName extends Newtype.WithoutValidationOf[AttrName]
type TestName = TestName.Type

extension (test: TestName)
  def name: String = test.unwrap.unwrap
  def check(flake: FlakeRef): Installable =
    Installable(flake, AttrPath.of(AttrName.make.orThrow("checks"), Nix.system, test.unwrap))

/** The tests there are, in tests/checks/. */
final case class KnownTests(all: NonEmptyVector[TestName]):
  given Argument[TestName] = Argument.from("test")(name =>
    all
      .find(_.name == name)
      .toValidNel(show"No test '$name' (there are: ${all.map(_.name).mkString_(" ")}).")
  )

object KnownTests:
  def discover(root: os.Path): IO[ScriptError, KnownTests] =
    blocking(os.list(root / "tests" / "checks").filter(f => os.isFile(f) && f.ext == "nix"))
      .flatMap(
        _.map(_.baseName).sorted.toVector
          .traverse(AttrName.make.asString(_).map(TestName(_)))
          .flatMap(NonEmptyVector.fromVector(_).toRight("no tests in tests/checks/"))
          .map(KnownTests(_))
          .orFail
      )

/** Whether a test that passed before (its result still in the Nix store, as nothing
  * it uses changed) runs again.
  */
enum Rerun:
  case IfChanged, Always

/** Tests run all at once or one after the other. */
enum Schedule:
  case Parallel, Sequential

enum Selection:
  /** Every test, after checking that the flake evaluates. */
  case All
  case Only(tests: NonEmptyVector[TestName])

/** What the script does, from its arguments. */
enum Mode:
  case ListTests
  case EvaluateOnly
  case Interactive(test: TestName)
  case Run(selection: Selection, rerun: Rerun, schedule: Schedule)

object Mode:
  /** The modes are alternatives: options of one mode given with another's are
    * refused (decline: "Unexpected option").
    */
  def command(known: KnownTests): Command[Mode] =
    import known.given
    val list = Opts.flag("list", "List the tests.", "l").as(ListTests)
    val evaluate = Opts
      .flag(
        "evaluate",
        "Only check that ./check-secrets.scala passes (no plaintext secret in what would " +
          "be pushed) and everything (every host, package and test) evaluates; no test " +
          "is run. Running all the tests does this first too.",
        "e"
      )
      .as(EvaluateOnly)
    val interactive = Opts
      .option[TestName](
        "interactive",
        "Boot this test's machines and open the test driver's Python shell instead of " +
          "running the script. There: `start_all()`, then e.g. `host.succeed(\"ls\")`, " +
          "`host.shell_interact()` (a shell in that machine, Ctrl-D to come back), " +
          "`test_script()` runs the test's script.",
        "i"
      )
      .map(Interactive(_))
    val run = (
      Opts
        .arguments[TestName]("test")
        .map(tests => Selection.Only(tests.toNev))
        .withDefault(Selection.All),
      Opts
        .flag(
          "force",
          "Run again even if it passed before (Nix keeps a passed test's result until " +
            "something it uses changes, and then doesn't run it again).",
          "f"
        )
        .as(Rerun.Always)
        .withDefault(Rerun.IfChanged),
      Opts
        .flag(
          "sequential",
          "Run the tests one after the other instead of all at once: for a machine " +
            "short of RAM (all at once take several GB) or cores; each test is quicker " +
            "and the output isn't interleaved (all at once, each line starts with its " +
            "test's name), but they take longer in all.",
          "s"
        )
        .as(Schedule.Sequential)
        .withDefault(Schedule.Parallel)
    ).mapN(Run.apply)

    Command(
      "test.scala",
      "Runs the VM tests (tests/checks/<test>.nix): the ones named, or all of them " +
        "after checking that everything evaluates."
    )(list orElse evaluate orElse interactive orElse run)

enum Outcome(val label: String):
  case Passed extends Outcome("passed")
  /** Its result was in the store already; nothing ran. */
  case PassedBefore extends Outcome("passed before")
  case Failed extends Outcome("FAILED")

object Main extends ScriptApp:
  def program(args: List[String]): IO[ScriptError, ExitCode] =
    for
      root <- Repo.root
      known <- KnownTests.discover(root)
      mode <- Cli.parse(Mode.command(known), args)
      code <- Tests(root, known).run(mode)
    yield code

final case class Tests(root: os.Path, known: KnownTests):
  val flake: FlakeRef = FlakeRef.of(root)

  def run(mode: Mode): IO[ScriptError, ExitCode] = mode match
    case Mode.ListTests =>
      known.all.traverse_(test => printOut(test.name)).as(ExitCode.success)
    case Mode.EvaluateOnly                   => evaluate.as(ExitCode.success)
    case Mode.Interactive(test)              => interactive(test)
    case Mode.Run(selection, rerun, schedule) => runTests(selection, rerun, schedule)

  /** No plaintext secret in what would be pushed (the repo is public), and
    * everything the flake has (every host, package and test) evaluates.
    */
  val evaluate: IO[ScriptError, Unit] =
    Proc.succeed(os.proc(root / "check-secrets.scala"), root) *>
      printErr("Evaluating the flake...") *>
      Proc.succeed(Nix("flake", "check", "--no-build", flake.unwrap), root)

  def interactive(test: TestName): IO[ScriptError, ExitCode] =
    val check = test.check(flake)
    val driver = check.copy(attribute = check.attribute / AttrName.make.orThrow("driverInteractive"))
    ZIO
      .acquireReleaseWith(blocking(os.temp.dir()))(dir => blocking(os.remove.all(dir))) { dir =>
        val link = dir / "driver"
        Proc.succeed(Nix("build", driver.render, "-o", link), root) *>
          Proc.foreground(os.proc(link / "bin" / "nixos-test-driver"), root)
      }
      .map(ExitCode(_))

  def runTests(selection: Selection, rerun: Rerun, schedule: Schedule): IO[ScriptError, ExitCode] =
    val runOne = (test: TestName) => runTest(test, rerun).tupleLeft(test)
    (for
      tests <- selection match
        // before spending time on the tests
        case Selection.All         => evaluate.as(known.all)
        case Selection.Only(tests) => tests.pure[ScriptIO]
      results <- schedule match
        case Schedule.Parallel   => tests.parTraverse(runOne)
        // Several at once compete for the CPU, RAM and disk.
        case Schedule.Sequential => tests.traverse(runOne)
    yield results).timed.flatMap { (total, results) =>
      val lines = results.map { case (test, (outcome, took)) =>
        "  %-20s %-14s %s".format(test.name, outcome.label, Time.format(took))
      } :+ "  %-35s %s".format("in all", Time.format(total))
      val failed = results.exists { case (_, (outcome, _)) => outcome == Outcome.Failed }
      printErr("\nTests:") *>
        lines.traverse_(printErr) *>
        ZIO.succeed(if failed then ExitCode.failure else ExitCode.success)
    }

  /** Runs a test, timing it by the wall clock: evaluating it, building what it
    * needs, booting the VMs and running the script.
    */
  def runTest(test: TestName, rerun: Rerun): UIO[(Outcome, Duration)] =
    val check = test.check(flake)
    (for
      cached <- Nix.isBuilt(check, root)
      // --rebuild needs a previous result to run again.
      rebuild = rerun == Rerun.Always && cached
      _ <- printErr(show"Running test ${test.name}...")
      code <- Proc.status(
        Nix("build", "-L", "--no-link", check.render, if rebuild then Seq("--rebuild") else Nil),
        root
      )
    yield
      if code != 0 then Outcome.Failed
      else if cached && !rebuild then Outcome.PassedBefore
      else Outcome.Passed
    ).timed.map(_.swap)
