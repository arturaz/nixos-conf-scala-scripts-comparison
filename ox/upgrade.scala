#!/usr/bin/env bash
//usr/bin/env true; exec "$(dirname "$0")/scala-lib/run" "$0" "$@" # bash runs this line; Scala sees a comment
// Updates everything this repo pins, tests the result, then deploys it:
//   1. flake.lock (nixpkgs, sops-nix);
//   2. the packages nixpkgs lacks (packages/), to the newest release each one's
//      package.nix accepts (`passthru.updateScript`), with their hashes;
//   3. the VM tests (test.scala: tests/, the flake's checks), stopping if one fails,
//      before the machine is touched;
//   4. deploy.scala.
// Commit the updated files afterwards (it lists them).
// Same arguments and env vars as deploy.scala, which does the deploying; wrong ones
// are refused before anything is updated.
// Usage: --help.
//> using file scala-lib

import cats.syntax.all.*
import com.monovore.decline.Command
import nixosconf.lib.*
import ox.{ExitCode, Ox, either}
import ox.either.ok

object Main extends ScriptApp:
  val updated = Seq("flake.lock", "packages")

  def command(known: KnownHosts): Command[DeployArgs] =
    Command(
      "upgrade.scala",
      "Updates flake.lock and packages/, runs the VM tests, then deploys (as " +
        "deploy.scala, with the same arguments)."
    )(DeployArgs.opts(known))

  def program(args: List[String])(using Ox): Either[ScriptError, ExitCode] = either {
    val root = Repo.root.ok()
    val known = KnownHosts.discover(root).ok()
    // Only checked here: deploy.scala gets the arguments as they are.
    Cli.parse(command(known), args).ok()
    val flake = FlakeRef.of(root).unwrap
    val notDeployed = (what: String) =>
      show"$what (see above). Nothing was deployed; the updated files are left as they " +
        show"are (undo with: jj restore ${updated.mkString(" ")})."
    Console.err.println("Updating flake.lock...")
    Proc
      .succeedOr(Nix("flake", "update", "--flake", flake), root)(_ =>
        "updating flake.lock failed (see above). Nothing was changed."
      )
      .ok()
    // Each package in packages/ declares how it's updated (`passthru.updateScript`:
    // which releases it accepts); the flake's update-packages runs them all.
    Console.err.println("Updating packages/...")
    Proc.succeedOr(Nix("run", show"$flake#update-packages"), root)(_ => notDeployed("updating packages/ failed")).ok()
    // All of them (a few minutes; needs KVM); also checks that every host's
    // configuration evaluates.
    Console.err.println("Running the VM tests...")
    Proc.succeedOr(os.proc(root / "test.scala"), root)(_ => notDeployed("a test failed")).ok()
    val changed = Vcs.changed(root, updated).ok()
    Console.err.println(
      show"Updated (commit them): ${if changed.isEmpty then "nothing" else changed.linesIterator.mkString(" ")}"
    )
    // As bash's exec would: Ctrl-C (e.g. at its password prompt) is deploy.scala's.
    exitCode(Proc.foreground(os.proc(root / "deploy.scala", args), root))
  }
