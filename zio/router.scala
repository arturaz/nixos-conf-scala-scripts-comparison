#!/usr/bin/env bash
//usr/bin/env true; exec "$(dirname "$0")/scala-lib/run" "$0" "$@" # bash runs this line; Scala sees a comment
// Sets the router up for a host: runs the host's `router.script`
// (modules/router/: the router's whole firewall, with the host's VMs' ports
// forwarded to it and its network kept out of the LAN; its name in the router's DNS) on
// the router over ssh. Changes nothing when the router already has exactly that; else
// it replaces the firewall, rules added there by hand included.
// deploy.scala runs it after switching. Before, and after if the script changed something,
// ./router-backup.scala backs the router's configuration up.
// The router is reached at the host's `router.ssh`; override with ROUTER=user@host.
// Usage: --help.
//> using file scala-lib

import nixosconf.lib.*
import zio.*
import zio.cli.{Command, HelpDoc}
import zio.json.*
import zio.nio.file.Path

final case class Args(host: HostName, router: Option[String])

object Args:
  def parse(known: KnownHosts, args: List[String]): IO[ScriptError, Args] =
    for
      host <- Cli.parse(
        Command("router.scala", known.argument).withHelp(
          "Runs the host's router.script (the router's whole firewall) on the router, " +
            "backing the router's configuration up before and (if it changed something) after."
        ),
        args,
        HelpDoc.h1("environment variables") + HelpDoc.descriptionList(
          HelpDoc.Span.text("ROUTER") -> HelpDoc.p("The router's ssh destination (default: the host's router.ssh).")
        )
      )
      router <- env("ROUTER")
    yield Args(host, router)

/** The host's `router.script` ("" when `router.address` isn't set) and `router.ssh`. */
final case class Router(script: String, ssh: String) derives JsonDecoder

object Main extends ScriptApp:
  def program(args: List[String]): IO[ScriptError, ExitCode] =
    for
      root <- Repo.root
      known <- KnownHosts.discover(root)
      args <- Args.parse(known, args)
      host = args.host.name
      // null for hosts without the router module: nothing to set up.
      router <- Nix.evalJson[Option[Router]](
        args.host.config(FlakeRef.of(root)),
        "c: if c ? router then { inherit (c.router) script ssh; } else null",
        root
      )(show"the configuration of $host doesn't evaluate (see above).")
      _ <- router.filter(_.script.nonEmpty) match
        case None         => printOut(show"$host has nothing to set up on the router (router.address isn't set).")
        case Some(router) => setUp(root, args.host, router.script, args.router.getOrElse(router.ssh))
    yield ExitCode.success

  def setUp(root: Path, host: HostName, script: String, router: String): IO[ScriptError, Unit] =
    // Backed up before, so what was changed by hand (which the script may undo) has its
    // own commit, and after, unless the script found the router unchanged (then the
    // second backup would only repeat the first). A failed backup doesn't stop it:
    // Forgejo, where it goes, may be restarting on the host being deployed.
    def backup(message: String) =
      Proc
        .status(Cmd((root / "router-backup.scala").toString, host.name, message), root, env = Map("ROUTER" -> router))
        .flatMap(code =>
          printErr(
            "warning: backing up the router failed (see above); retry with: " +
              show"./router-backup.scala ${host.name}"
          ).when(code != 0)
        )
    for
      _ <- backup(show"Before ./router.scala ${host.name}")
      _ <- printErr(show"Setting up $router for ${host.name}...")
      // RouterOS's ssh exits with 0 even when the script fails, so it's judged by its
      // output.
      out <- Proc.outputOr(Cmd("ssh", router, script), root, mergeErr = true)(out =>
        show"could not run the script on $router: $out"
      )
      _ <- printOut(out)
      _ <- failUnless(out.contains("nixos-conf: "))(show"the script failed on $router (see above).")
      _ <- backup(show"./router.scala ${host.name}").unless(out.contains("nixos-conf: unchanged"))
    yield ()
