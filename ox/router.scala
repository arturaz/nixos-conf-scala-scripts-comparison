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

import cats.syntax.all.*
import com.monovore.decline.{Command, Opts}
import nixosconf.lib.*
import ox.{ExitCode, Ox, either}
import ox.either.ok
import upickle.default.ReadWriter

final case class Args(host: HostName, router: Option[String])

object Args:
  def command(known: KnownHosts): Command[Args] =
    import known.given
    Command(
      "router.scala",
      "Runs the host's router.script (the router's whole firewall) on the router, " +
        "backing the router's configuration up before and (if it changed something) after."
    )(
      (
        Opts.argument[HostName]("name"),
        Opts.env[String]("ROUTER", "The router's ssh destination (default: the host's router.ssh).").orNone
      ).mapN(Args.apply)
    )

/** The host's `router.script` ("" when `router.address` isn't set) and `router.ssh`. */
final case class Router(script: String, ssh: String) derives ReadWriter

object Main extends ScriptApp:
  def program(args: List[String])(using Ox): Either[ScriptError, ExitCode] = either {
    val root = Repo.root.ok()
    val known = KnownHosts.discover(root).ok()
    val parsed = Cli.parse(Args.command(known), args).ok()
    val host = parsed.host.name
    // null for hosts without the router module: nothing to set up.
    val router = Nix
      .evalJson[Option[Router]](
        parsed.host.config(FlakeRef.of(root)),
        "c: if c ? router then { inherit (c.router) script ssh; } else null",
        root
      )(show"the configuration of $host doesn't evaluate (see above).")
      .ok()
    router.filter(_.script.nonEmpty) match
      case None         => println(show"$host has nothing to set up on the router (router.address isn't set).")
      case Some(router) => setUp(root, parsed.host, router.script, parsed.router.getOrElse(router.ssh)).ok()
    ExitCode.Success
  }

  def setUp(root: os.Path, host: HostName, script: String, router: String): Either[ScriptError, Unit] =
    // Backed up before, so what was changed by hand (which the script may undo) has its
    // own commit, and after, unless the script found the router unchanged (then the
    // second backup would only repeat the first). A failed backup doesn't stop it:
    // Forgejo, where it goes, may be restarting on the host being deployed.
    def backup(message: String): Unit =
      val code = Proc.status(
        os.proc(root / "router-backup.scala", host.name, message),
        root,
        env = Map("ROUTER" -> router)
      )
      if code != 0 then
        Console.err.println(
          "warning: backing up the router failed (see above); retry with: " +
            show"./router-backup.scala ${host.name}"
        )
    either {
      backup(show"Before ./router.scala ${host.name}")
      Console.err.println(show"Setting up $router for ${host.name}...")
      // RouterOS's ssh exits with 0 even when the script fails, so it's judged by its
      // output.
      val out = Proc
        .outputOr(os.proc("ssh", router, script), root, mergeErr = true)(out =>
          show"could not run the script on $router: $out"
        )
        .ok()
      println(out)
      failUnless(out.contains("nixos-conf: "))(show"the script failed on $router (see above).").ok()
      if !out.contains("nixos-conf: unchanged") then backup(show"./router.scala ${host.name}")
    }
