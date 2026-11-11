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

import kyo.*
import nixosconf.lib.{*, given}

object Main extends ScriptApp[DeployOptions]:
  val updated = Seq("flake.lock", "packages")

  override def name: String = "upgrade.scala"
  def description: String =
    "Updates flake.lock and packages/, runs the VM tests, then deploys (as " +
      "deploy.scala, with the same arguments).\n\n" + DeployArgs.help

  def program(options: DeployOptions, args: Chunk[String]): Int < (Script & Scope) =
    direct {
      val root = Repo.root.now
      val known = KnownHosts.discover(root).now
      // Only checked here: deploy.scala gets the arguments as they are.
      val _ = DeployArgs.parse(known, args).now
      val flake = FlakeRef.of(root).unwrap
      def notDeployed(what: String) =
        show"$what (see above). Nothing was deployed; the updated files are left as they " +
          show"are (undo with: jj restore ${updated.mkString(" ")})."
      Console.printLineErr("Updating flake.lock...").now
      Proc
        .succeedOr(Nix("flake", "update", "--flake", flake), root)(_ =>
          "updating flake.lock failed (see above). Nothing was changed."
        )
        .now
      // Each package in packages/ declares how it's updated (`passthru.updateScript`:
      // which releases it accepts); the flake's update-packages runs them all.
      Console.printLineErr("Updating packages/...").now
      Proc.succeedOr(Nix("run", show"$flake#update-packages"), root)(_ => notDeployed("updating packages/ failed")).now
      // All of them (a few minutes; needs KVM); also checks that every host's
      // configuration evaluates.
      Console.printLineErr("Running the VM tests...").now
      Proc.succeedOr(Command((root / "test.scala").toString), root)(_ => notDeployed("a test failed")).now
      val changed = Vcs.changed(root, updated).now
      Console
        .printLineErr(
          show"Updated (commit them): ${if changed.isEmpty then "nothing" else changed.linesIterator.mkString(" ")}"
        )
        .now
      // As bash's exec would: Ctrl-C (e.g. at its password prompt) is deploy.scala's.
      Proc.foreground(Command(Seq((root / "deploy.scala").toString) ++ args*), root).now
    }
