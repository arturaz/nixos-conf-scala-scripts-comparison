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

import nixosconf.lib.*
import zio.*

object Main extends ScriptApp:
  val updated = Seq("flake.lock", "packages")

  def program(args: List[String]): IO[ScriptError, ExitCode] =
    for
      root <- Repo.root
      known <- KnownHosts.discover(root)
      // Only checked here: deploy.scala gets the arguments as they are.
      _ <- DeployArgs.parse(
        known,
        "upgrade.scala",
        "Updates flake.lock and packages/, runs the VM tests, then deploys (as " +
          "deploy.scala, with the same arguments).",
        args
      )
      flake = FlakeRef.of(root).unwrap
      notDeployed = (what: String) =>
        show"$what (see above). Nothing was deployed; the updated files are left as they " +
          show"are (undo with: jj restore ${updated.mkString(" ")})."
      _ <- printErr("Updating flake.lock...")
      _ <- Proc.succeedOr(Nix("flake", "update", "--flake", flake), root)(_ =>
        "updating flake.lock failed (see above). Nothing was changed."
      )
      // Each package in packages/ declares how it's updated (`passthru.updateScript`:
      // which releases it accepts); the flake's update-packages runs them all.
      _ <- printErr("Updating packages/...")
      _ <- Proc.succeedOr(Nix("run", show"$flake#update-packages"), root)(_ => notDeployed("updating packages/ failed"))
      // All of them (a few minutes; needs KVM); also checks that every host's
      // configuration evaluates.
      _ <- printErr("Running the VM tests...")
      _ <- Proc.succeedOr(Cmd((root / "test.scala").toString), root)(_ => notDeployed("a test failed"))
      changed <- Vcs.changed(root, updated)
      _ <- printErr(
        show"Updated (commit them): ${if changed.isEmpty then "nothing" else changed.linesIterator.mkString(" ")}"
      )
      // As bash's exec would: Ctrl-C (e.g. at its password prompt) is deploy.scala's.
      code <- Proc.foreground(Cmd((root / "deploy.scala").toString) ++ args, root)
    yield ExitCode(code)
