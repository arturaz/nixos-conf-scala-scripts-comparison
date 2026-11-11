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

import caseapp.{ExtraName, HelpMessage, ValueDescription}
import kyo.*
import nixosconf.lib.{*, given}
import yantl.Newtype

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

/** The tests there are, in tests/checks/ (at least one). */
final case class KnownTests private (all: Chunk[TestName]):
  /** The test named `name`, or a usage error. */
  def parse(name: String)(using Frame): TestName < Abort[Stop] =
    Abort.get(
      all
        .find(_.name == name)
        .toRight(Fail(show"No test '$name' (there are: ${all.map(_.name).mkString(" ")}). (see --help)"))
    )

object KnownTests:
  def discover(root: Path)(using Frame): KnownTests < (Abort[Stop] & PathRead) =
    direct {
      val files = (root / "tests" / "checks").list.now.filter(f => f.isRegularFile.now && f.extName.contains(".nix"))
      val names = files.flatMap(_.name.toOption).map(_.stripSuffix(".nix")).sorted
      val tests = Chunk.from(names.map(n => AttrName.make.asString(n).map(TestName(_)).orFail.now))
      if tests.isEmpty then fail("no tests in tests/checks/").now else KnownTests(tests)
    }

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
  case Only(tests: Chunk[TestName])

/** What the script does, from its arguments. */
enum Mode:
  case ListTests
  case EvaluateOnly
  case Interactive(test: TestName)
  case Run(selection: Selection, rerun: Rerun, schedule: Schedule)

final case class Options(
    @ExtraName("l")
    @HelpMessage("List the tests.")
    list: Boolean = false,
    @ExtraName("e")
    @HelpMessage(
      "Only check that ./check-secrets.scala passes (no plaintext secret in what would " +
        "be pushed) and everything (every host, package and test) evaluates; no test " +
        "is run. Running all the tests does this first too."
    )
    evaluate: Boolean = false,
    @ExtraName("i")
    @ValueDescription("test")
    @HelpMessage(
      "Boot this test's machines and open the test driver's Python shell instead of " +
        "running the script. There: `start_all()`, then e.g. `host.succeed(\"ls\")`, " +
        "`host.shell_interact()` (a shell in that machine, Ctrl-D to come back), " +
        "`test_script()` runs the test's script."
    )
    interactive: Option[String] = None,
    @ExtraName("f")
    @HelpMessage(
      "Run again even if it passed before (Nix keeps a passed test's result until " +
        "something it uses changes, and then doesn't run it again)."
    )
    force: Boolean = false,
    @ExtraName("s")
    @HelpMessage(
      "Run the tests one after the other instead of all at once: for a machine " +
        "short of RAM (all at once take several GB) or cores; each test is quicker " +
        "and the output isn't interleaved (all at once, each line starts with its " +
        "test's name), but they take longer in all."
    )
    sequential: Boolean = false
)

object Mode:
  /** The modes are alternatives: options of one mode given with another's are
    * refused.
    */
  def parse(known: KnownTests, options: Options, args: Chunk[String])(using Frame): Mode < Abort[Stop] =
    val runOptions = options.force || options.sequential || args.nonEmpty
    val modes = Seq(options.list, options.evaluate, options.interactive.isDefined, runOptions).count(identity)
    direct {
      if modes > 1 then
        usage("--list, --evaluate, --interactive and running tests (-f, -s, test names) are alternatives").now
      if options.list then ListTests
      else if options.evaluate then EvaluateOnly
      else
        options.interactive match
          case Some(name) => Interactive(known.parse(name).now)
          case None =>
            val tests = args.map(known.parse(_).now)
            Run(
              if tests.isEmpty then Selection.All else Selection.Only(tests),
              if options.force then Rerun.Always else Rerun.IfChanged,
              if options.sequential then Schedule.Sequential else Schedule.Parallel
            )
    }

enum Outcome(val label: String):
  case Passed extends Outcome("passed")
  /** Its result was in the store already; nothing ran. */
  case PassedBefore extends Outcome("passed before")
  case Failed extends Outcome("FAILED")

object Main extends ScriptApp[Options]:
  override def name: String = "test.scala"
  def description: String =
    """Runs the VM tests (tests/checks/<test>.nix): the ones named, or all of them after
      |checking that everything evaluates.
      |
      |Arguments: [test...]""".stripMargin

  def program(options: Options, args: Chunk[String]): Int < (Script & Scope) =
    direct {
      val root = Repo.root.now
      val known = KnownTests.discover(root).now
      val mode = Mode.parse(known, options, args).now
      Tests(root, known).run(mode).now
    }

final case class Tests(root: Path, known: KnownTests):
  val flake: FlakeRef = FlakeRef.of(root)

  def run(mode: Mode)(using Frame): Int < (Script & Scope) = mode match
    case Mode.ListTests =>
      Kyo.foreachDiscard(known.all)(test => Console.printLine(test.name)).andThen(0)
    case Mode.EvaluateOnly                    => evaluate.andThen(0)
    case Mode.Interactive(test)               => interactive(test)
    case Mode.Run(selection, rerun, schedule) => runTests(selection, rerun, schedule)

  /** No plaintext secret in what would be pushed (the repo is public), and
    * everything the flake has (every host, package and test) evaluates.
    */
  def evaluate(using Frame): Unit < Proc.Effects =
    direct {
      Proc.succeed(Command((root / "check-secrets.scala").toString), root).now
      Console.printLineErr("Evaluating the flake...").now
      Proc.succeed(Nix("flake", "check", "--no-build", flake.unwrap), root).now
    }

  def interactive(test: TestName)(using Frame): Int < (Script & Scope) =
    val check = test.check(flake)
    val driver = check.copy(attribute = check.attribute / AttrName.make.orThrow("driverInteractive"))
    direct {
      val link = Path.tempDir("test").now / "driver"
      Proc.succeed(Nix("build", driver.render, "-o", link.toString), root).now
      Proc.foreground(Command((link / "bin" / "nixos-test-driver").toString), root).now
    }

  def runTests(selection: Selection, rerun: Rerun, schedule: Schedule)(using Frame): Int < Proc.Effects =
    def runOne(test: TestName) = runTest(test, rerun).map((test, _))
    direct {
      val (total, results) = Timed(direct {
        val tests = selection match
          case Selection.All =>
            evaluate.now // before spending time on the tests
            known.all
          case Selection.Only(tests) => tests
        schedule match
          case Schedule.Parallel => Async.foreach(tests, tests.size)(runOne).now
          // Several at once compete for the CPU, RAM and disk.
          case Schedule.Sequential => Kyo.foreach(tests)(runOne).now
      }).now
      val lines = results.map { case (test, (outcome, took)) =>
        "  %-20s %-14s %s".format(test.name, outcome.label, Time.format(took))
      } :+ "  %-35s %s".format("in all", Time.format(total))
      Console.printLineErr("\nTests:").now
      Kyo.foreachDiscard(lines)(Console.printLineErr(_)).now
      if results.exists { case (_, (outcome, _)) => outcome == Outcome.Failed } then 1 else 0
    }

  /** Runs a test, timing it by the wall clock: evaluating it, building what it
    * needs, booting the VMs and running the script.
    */
  def runTest(test: TestName, rerun: Rerun)(using Frame): (Outcome, Duration) < Proc.Effects =
    val check = test.check(flake)
    Timed(direct {
      val cached = Nix.isBuilt(check, root).now
      // --rebuild needs a previous result to run again.
      val rebuild = rerun == Rerun.Always && cached
      Console.printLineErr(show"Running test ${test.name}...").now
      val code = Proc
        .status(Nix(Seq("build", "-L", "--no-link", check.render) ++ (if rebuild then Seq("--rebuild") else Nil)*), root)
        .now
      if code != 0 then Outcome.Failed
      else if cached && !rebuild then Outcome.PassedBefore
      else Outcome.Passed
    }).map(_.swap)
