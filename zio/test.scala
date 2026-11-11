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

import nixosconf.lib.{*, given}
import yantl.Newtype
import zio.*
import zio.cli.{Args, Command, HelpDoc, Options, ValidationError, ValidationErrorType}
import zio.nio.file.{Files, Path}

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
final case class KnownTests(all: NonEmptyChunk[TestName]):
  def find(name: String): Either[String, TestName] =
    all.find(_.name == name).toRight(show"No test '$name' (there are: ${all.map(_.name).mkString(" ")}).")

object KnownTests:
  def discover(root: Path): IO[ScriptError, KnownTests] =
    for
      files <- Files
        .list(root / "tests" / "checks")
        .filterZIO(f => Files.isRegularFile(f).map(_ && f.filename.toString.endsWith(".nix")))
        .runCollect
        .orDie
      tests <- ZIO
        .foreach(files.map(_.filename.toString.stripSuffix(".nix")).sorted)(name =>
          ZIO.fromEither(AttrName.make.asString(name)).map(TestName(_))
        )
        .flatMap(tests => ZIO.fromOption(NonEmptyChunk.fromChunk(tests)).orElseFail("no tests in tests/checks/"))
        .mapError(Fail(_))
    yield KnownTests(tests)

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
  case Only(tests: NonEmptyChunk[TestName])

/** What the script does, from its arguments. */
enum Mode:
  case ListTests
  case EvaluateOnly
  case Interactive(test: TestName)
  case Run(selection: Selection, rerun: Rerun, schedule: Schedule)

object Mode:
  /** The modes are alternatives: options of one mode given with another's are
    * refused. zio-cli can't say so itself (its `orElse` of options takes the first
    * that parses, and a flag always does, as false), so every option is parsed and
    * then they're checked here.
    */
  def parse(known: KnownTests, args: List[String]): IO[ScriptError, Mode] =
    val test = (name: String) =>
      known.find(name).left.map(e => ValidationError(ValidationErrorType.InvalidValue, HelpDoc.p(e)))
    val command = Command(
      "test.scala",
      (Options.boolean("list").alias("l") ?? "List the tests.") ++
      (Options.boolean("evaluate").alias("e") ??
        ("Only check that ./check-secrets.scala passes (no plaintext secret in what would " +
          "be pushed) and everything (every host, package and test) evaluates; no test " +
          "is run. Running all the tests does this first too.")) ++
      (Options.text("interactive").alias("i").withPseudoName("test").mapOrFail(test).optional ??
        ("Boot this test's machines and open the test driver's Python shell instead of " +
          "running the script. There: `start_all()`, then e.g. `host.succeed(\"ls\")`, " +
          "`host.shell_interact()` (a shell in that machine, Ctrl-D to come back), " +
          "`test_script()` runs the test's script.")) ++
      (Options.boolean("force").alias("f") ??
        ("Run again even if it passed before (Nix keeps a passed test's result until " +
          "something it uses changes, and then doesn't run it again).")) ++
      (Options.boolean("sequential").alias("s") ??
        ("Run the tests one after the other instead of all at once: for a machine " +
          "short of RAM (all at once take several GB) or cores; each test is quicker " +
          "and the output isn't interleaved (all at once, each line starts with its " +
          "test's name), but they take longer in all.")),
      (Args.text("test") ?? "A test to run (default: all of them).").mapOrFail(name =>
        known.find(name).left.map(HelpDoc.p)
      ).*
    ).withHelp(
      "Runs the VM tests (tests/checks/<test>.nix): the ones named, or all of them " +
        "after checking that everything evaluates."
    )
    Cli.parse(command, args).flatMap { case ((list, evaluate, interactive, force, sequential), tests) =>
      val run = Run(
        NonEmptyChunk.fromIterableOption(tests).fold(Selection.All)(Selection.Only(_)),
        if force then Rerun.Always else Rerun.IfChanged,
        if sequential then Schedule.Sequential else Schedule.Parallel
      )
      val chosen = Seq(
        Option.when(list)(ListTests),
        Option.when(evaluate)(EvaluateOnly),
        interactive.map(Interactive(_)),
        Option.when(force || sequential || tests.nonEmpty)(run)
      ).flatten
      chosen match
        case Seq()     => ZIO.succeed(run)
        case Seq(mode) => ZIO.succeed(mode)
        case _ =>
          fail("-l, -e, -i and running tests (-f, -s, test names) are alternatives (see --help).")
    }

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
      mode <- Mode.parse(known, args)
      code <- Tests(root, known).run(mode)
    yield code

final case class Tests(root: Path, known: KnownTests):
  val flake: FlakeRef = FlakeRef.of(root)

  def run(mode: Mode): IO[ScriptError, ExitCode] = mode match
    case Mode.ListTests                       => ZIO.foreachDiscard(known.all)(test => printOut(test.name)).as(ExitCode.success)
    case Mode.EvaluateOnly                    => evaluate.as(ExitCode.success)
    case Mode.Interactive(test)               => interactive(test)
    case Mode.Run(selection, rerun, schedule) => runTests(selection, rerun, schedule)

  /** No plaintext secret in what would be pushed (the repo is public), and
    * everything the flake has (every host, package and test) evaluates.
    */
  val evaluate: IO[ScriptError, Unit] =
    Proc.succeed(Cmd((root / "check-secrets.scala").toString), root) *>
      printErr("Evaluating the flake...") *>
      Proc.succeed(Nix("flake", "check", "--no-build", flake.unwrap), root)

  def interactive(test: TestName): IO[ScriptError, ExitCode] =
    val check = test.check(flake)
    val driver = check.copy(attribute = check.attribute / AttrName.make.orThrow("driverInteractive"))
    ZIO.scoped(
      for
        dir <- Files.createTempDirectoryScoped(None, Nil).orDie
        link = dir / "driver"
        _ <- Proc.succeed(Nix("build", driver.render, "-o", link.toString), root)
        code <- Proc.foreground(Cmd((link / "bin" / "nixos-test-driver").toString), root)
      yield ExitCode(code)
    )

  def runTests(selection: Selection, rerun: Rerun, schedule: Schedule): IO[ScriptError, ExitCode] =
    val runOne = (test: TestName) => runTest(test, rerun).map(test -> _)
    (for
      tests <- selection match
        // before spending time on the tests
        case Selection.All         => evaluate.as(known.all)
        case Selection.Only(tests) => ZIO.succeed(tests)
      results <- schedule match
        case Schedule.Parallel => ZIO.foreachPar(tests.toChunk)(runOne)
        // Several at once compete for the CPU, RAM and disk.
        case Schedule.Sequential => ZIO.foreach(tests.toChunk)(runOne)
    yield results).timed.flatMap { (total, results) =>
      val lines = results.map { case (test, (outcome, took)) =>
        "  %-20s %-14s %s".format(test.name, outcome.label, Time.format(took))
      } :+ "  %-35s %s".format("in all", Time.format(total))
      val failed = results.exists { case (_, (outcome, _)) => outcome == Outcome.Failed }
      printErr("\nTests:") *>
        ZIO.foreachDiscard(lines)(printErr) *>
        ZIO.succeed(if failed then ExitCode.failure else ExitCode.success)
    }

  /** Runs a test, timing it by the wall clock: evaluating it, building what it
    * needs, booting the VMs and running the script.
    */
  def runTest(test: TestName, rerun: Rerun): IO[ScriptError, (Outcome, Duration)] =
    val check = test.check(flake)
    (for
      cached <- Nix.isBuilt(check, root)
      // --rebuild needs a previous result to run again.
      rebuild = rerun == Rerun.Always && cached
      _ <- printErr(show"Running test ${test.name}...")
      code <- Proc.status(
        Nix("build", "-L", "--no-link", check.render) ++ (if rebuild then Seq("--rebuild") else Nil),
        root
      )
    yield
      if code != 0 then Outcome.Failed
      else if cached && !rebuild then Outcome.PassedBefore
      else Outcome.Passed
    ).timed.map(_.swap)
