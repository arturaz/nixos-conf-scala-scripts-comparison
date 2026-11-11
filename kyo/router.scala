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

import kyo.*
import nixosconf.lib.{*, given}

/** The host's `router.script` ("" when `router.address` isn't set) and `router.ssh`. */
final case class Router(script: String, ssh: String) derives Schema

/** router.scala's options: none but --help. */
final case class Options()

object Main extends ScriptApp[Options]:
  override def name: String = "router.scala"
  def description: String =
    """Runs the host's router.script (the router's whole firewall) on the router, backing
      |the router's configuration up before and (if it changed something) after.
      |
      |Arguments: <name>
      |Environment variables:
      |  ROUTER=user@host  The router's ssh destination (default: the host's router.ssh).""".stripMargin

  def program(options: Options, args: Chunk[String]): Int < (Script & Scope) =
    direct {
      val root = Repo.root.now
      val known = KnownHosts.discover(root).now
      val host = args.toList match
        case List(name) => known.parse(name).now
        case _          => usage("expected <name>").now
      val routerOverride = env("ROUTER").now
      // null for hosts without the router module: nothing to set up.
      val router = Nix
        .evalJson[Maybe[Router]](
          host.config(FlakeRef.of(root)),
          "c: if c ? router then { inherit (c.router) script ssh; } else null",
          root
        )(show"the configuration of ${host.name} doesn't evaluate (see above).")
        .now
      router.filter(_.script.nonEmpty).toOption match
        case None =>
          Console.printLine(show"${host.name} has nothing to set up on the router (router.address isn't set).").now
        case Some(router) => setUp(root, host, router.script, routerOverride.getOrElse(router.ssh)).now
      0
    }

  def setUp(root: Path, host: HostName, script: String, router: String)(using Frame): Unit < Proc.Effects =
    // Backed up before, so what was changed by hand (which the script may undo) has its
    // own commit, and after, unless the script found the router unchanged (then the
    // second backup would only repeat the first). A failed backup doesn't stop it:
    // Forgejo, where it goes, may be restarting on the host being deployed.
    def backup(message: String): Unit < Proc.Effects =
      direct {
        val code = Proc
          .status(
            Command((root / "router-backup.scala").toString, host.name, message),
            root,
            env = Map("ROUTER" -> router)
          )
          .now
        if code != 0 then
          Console
            .printLineErr(
              "warning: backing up the router failed (see above); retry with: " +
                show"./router-backup.scala ${host.name}"
            )
            .now
      }
    direct {
      backup(show"Before ./router.scala ${host.name}").now
      Console.printLineErr(show"Setting up $router for ${host.name}...").now
      // RouterOS's ssh exits with 0 even when the script fails, so it's judged by its
      // output.
      val out = Proc
        .outputOr(Command("ssh", router, script), root, mergeErr = true)(out =>
          show"could not run the script on $router: $out"
        )
        .now
      Console.printLine(out).now
      if !out.contains("nixos-conf: ") then fail(show"the script failed on $router (see above).").now
      if !out.contains("nixos-conf: unchanged") then backup(show"./router.scala ${host.name}").now
    }
