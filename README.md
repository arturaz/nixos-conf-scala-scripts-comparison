# One set of real Scala scripts, seven ways

The same ~1,350 lines of Scala 3 written seven ways:
- **cats-effect** (with `EitherT`);
- **cats-effect + cats-mtl**;
- **cats-effect + cats-effect-cps** (async/await over `EitherT`);
- **kyo**;
- **ZIO** (native ecosystem);
- **ZIO with cats interop**;
- **Ox**: no effect runtime, direct style on virtual threads.

Each variant was run through the same checks and gives the same results, so the
comparison is about the code and the libraries, not about features.

It's not a benchmark or a toy. These are the scripts that manage my NixOS machines, and
they do the unglamorous things CLI tools do:

| script | what it does | exercises |
|---|---|---|
| `deploy.scala` | evaluates a NixOS configuration, builds it on a remote machine over ssh, activates it with sudo | a scoped ssh session (ControlMaster) cleaned up on exit, a memoized password prompt, many subprocesses, JSON from `nix eval`, a long chain of steps that can fail, each with its own message |
| `test.scala` | runs NixOS VM tests in parallel or one by one, then prints a summary | mutually exclusive CLI modes, parallelism, timing |
| `check-secrets.scala` | refuses to let a secret be pushed (scans unpushed commits) | lots of `git`/`jj` calls; must fail closed (exit 2 if it couldn't look) |
| `router.scala`, `router-backup.scala` | apply a firewall script to a MikroTik router, back its config up into git | ssh, scp, git, a keyring, sops |
| `upgrade.scala` | update, test, then hand the terminal to `deploy.scala` | Ctrl-C belonging to the child process |

Shared code is in each variant's `scala-lib/`: the entry point (`Script.scala`), processes
(`Proc.scala`), CLI (`Cli.scala`), nix (`Nix.scala`) and so on.

## The variants

| directory | stack | what's different from `cats-effect/` |
|---|---|---|
| [`cats-effect/`](cats-effect) | cats-effect 3.7.1, decline 2.6.2, os-lib 0.11.10, upickle 4.4.3 | the baseline, and what my configuration uses: `IO[Either[ScriptError, A]]` composed with `EitherT` |
| [`cats-mtl/`](cats-mtl) | the same + cats-mtl 1.7.0 | only the errors: plain `IO[A]` with `(using Raise[IO, ScriptError])`, `Handle.allow` at the top |
| [`cats-cps/`](cats-cps) | the same + cats-effect-cps 0.5.0 (dotty-cps-async) | only the syntax: the same `EitherT` errors, written as `async[ScriptT] { … .await … }`. **Scala 3.8.4**, see below |
| [`kyo/`](kyo) | kyo 1.0.0-RC7: kyo-core, kyo-direct, kyo-system, kyo-schema-json, kyo-case-app | everything: kyo effects in direct style, kyo-system for processes and files, case-app for the CLI |
| [`zio/`](zio) | zio 2.1.27, zio-streams, zio-process 0.8.1, zio-nio 2.0.2, zio-cli 0.8.2, zio-json 1.1.2 | everything: the native ZIO stack |
| [`zio-interop/`](zio-interop) | zio 2.1.27 + zio-interop-cats 23.1.0.15, decline, os-lib, upickle | only the runtime: ZIO's `IO[ScriptError, A]` instead of cats-effect, with cats syntax (`traverse_`, `whenA`, `ifM`, `parTraverse`) running on ZIO |
| [`ox/`](ox) | Ox 1.1.0, decline, os-lib, upickle (cats-core only for decline and `show`) | no effect runtime: plain blocking code on virtual threads, errors as `Either` written with `either { … .ok() … }`, Ctrl-C and resources via `OxApp` and scopes |

All of them use JDK 27, `-Werror`, and [yantl](https://github.com/arturaz/yantl) for
newtypes. All use Scala 3.9.0 except `cats-cps/`, which needs 3.8.4. The style is the same
throughout: parse, don't validate; enums for options; expected failures are values; a
thrown exception is a bug; and `show"..."` instead of `s"..."`, so only values with a
deliberate rendering go into messages.

### Reading it

The most useful thing to do is to diff two variants:

```sh
diff -ru cats-effect zio
diff -u cats-effect/deploy.scala ox/deploy.scala
```

The variants differ only in what their libraries make different. Comments, messages and
structure are kept as close as possible, so a diff shows the cost of the stack.

### Running it

Each script starts with a bash/Scala header that hands it to `scala-lib/run`. That compiles
it once with [scala-cli](https://scala-cli.virtuslab.org/) into an assembly jar plus a JDK
AOT cache (in `~/.cache/nixos-conf/scala/`), then starts it in 0.1–0.2 s. The scripts find
their "repository" as the nearest `flake.nix` above them, so this repo has a placeholder
`flake.nix`, `hosts/example/` and `tests/checks/example.nix`. With them these work in every
variant:

```sh
./zio/test.scala --help
./zio/test.scala -l          # lists "example"
./zio/deploy.scala nope      # "No host 'nope' (there are: example)."
```

Anything that actually calls `nix`, `ssh` or the router needs the real configuration,
which isn't public. Note that the variants share the cache directory per script name, so
switching between variants recompiles.

## How it was checked

Each variant was ported from the cats-effect version, then run against the real
repository:

- **check-secrets:** passes on the real repository and catches a planted token. All seven
  give **byte-identical findings** on the same planted secrets, and exit 2 when there's no
  repository to look at.
- **test.scala:** `-l`, `-e` (`nix flake check`), two tests in parallel, and a forced real
  VM test (boots QEMU VMs, ~20 s). Conflicting modes, unknown tests, etc. exit 1 with a
  message.
- **deploy.scala:** against an unreachable host it says it couldn't connect. Interrupted
  with SIGINT (`timeout -s INT`), it exits **130**, the ssh child process is gone, and its
  temporary directory is removed. Bad arguments and env vars exit 1.
- **router.scala:** against an unresolvable router, the backup warns and carries on, then
  the script step fails with the right message.
- **Not run:** a real deploy, a real router change and `upgrade.scala` past argument
  parsing (they touch real machines). Those code paths were reviewed line by line against
  the baseline.

## Numbers

### Code size (non-blank, non-comment lines)

| | cats-effect | cats-mtl | cats-cps | kyo | zio | zio-interop | ox |
|---|---:|---:|---:|---:|---:|---:|---:|
| check-secrets.scala | 307 | 304 | 293 | 278 | 286 | 299 | **273** |
| deploy.scala | 337 | 327 | 330 | 340 | **293** | 320 | 302 |
| router-backup.scala | 192 | 183 | 180 | 185 | 194 | 183 | **173** |
| router.scala | 65 | 65 | 70 | 65 | **59** | 64 | 64 |
| test.scala | 168 | 165 | 172 | 166 | **158** | 163 | 165 |
| upgrade.scala | 39 | 39 | 41 | 36 | **34** | 38 | 38 |
| **scripts** | 1108 | 1083 | 1086 | 1070 | 1024 | 1067 | **1015** |
| scala-lib | 286 | 282 | 294 | 333 | 320 | 280 | **268** |
| **total** | 1394 | 1365 | 1380 | 1403 | 1344 | 1347 | **1283** |

What adds up, per variant:

| | per-step plumbing | per-definition | other |
|---|---|---|---|
| cats-effect | **157** `.toEitherT` / `.liftTScriptError` / `.value` / `EitherT(…)` | – | – |
| cats-mtl | none | 54 `(using CanFail)` | – |
| cats-cps | 117 `.await` + 90 `.awaitIO` | 44 `async[ScriptT] { … }` | 3 `.toVector` and 4 `val` bindings to dodge macro quirks |
| kyo | 248 `.now` | 76 `(using Frame)` | – |
| zio | none | – | 26 `.orDie` (zio-nio's typed IOExceptions) |
| zio-interop | none | – | 7 `.orDie`, 4 type ascriptions |
| ox | 93 `.ok()` | 25 `either { … }` | – |

For reference, the version before `EitherT`, which threw its `Fail`/`Exit` as exceptions,
was 1345 lines.

### Build and runtime

Measured on one machine (AMD Ryzen 9 7950X, 64 GB, JDK 27.0.1), all seven in the same
session, with the AOT cache. Startup and run times are averages of 12 runs; compile times
are 2 runs, without scala-cli's compile server. Treat differences under ~10% as noise.

| | cats-effect | cats-mtl | cats-cps | kyo | zio | zio-interop | ox |
|---|---:|---:|---:|---:|---:|---:|---:|
| cold compile, check-secrets.scala | 3.99 s | 3.97 s | 4.86 s | 5.35 s | **3.80 s** | 4.13 s | 3.95 s |
| cold compile, deploy.scala | 4.27 s | 4.24 s | 5.25 s | 6.04 s | **4.04 s** | 4.41 s | 4.18 s |
| assembly jar (test.scala) | 25.0 MB | 25.1 MB | 26.2 MB | 29.1 MB | **21.3 MB** | 39.3 MB | 22.6 MB |
| `test.scala -l` wall time | 0.140 s | 0.136 s | 0.140 s | 0.205 s | 0.152 s | 0.160 s | **0.089 s** |
| `test.scala --help` wall time | 0.130 s | 0.135 s | 0.139 s | **0.089 s** | 0.149 s | 0.130 s | **0.089 s** |
| `check-secrets.scala` on a small repo (≈10 git calls) | 0.335 s | 0.330 s | 0.339 s | 0.430 s | 0.365 s | 0.353 s | **0.268 s** |
| `check-secrets.scala` max RSS | 88 MB | 91 MB | 92 MB | 130 MB | 87 MB | 92 MB | **75 MB** |

All of these are far below what a user of these scripts would notice next to `nix eval`
(seconds) or a VM test (minutes); performance doesn't decide anything here. Still, the
pattern is clear: **with no effect runtime to start, Ox is the fastest and lightest**,
~50 ms faster to start than the cats-effect and ZIO variants.

## Findings

### The same steps, seven ways

The start of `deploy.scala`'s main sequence:

```scala
// cats-effect: every step is lifted into EitherT by hand
def steps: IO[Either[ScriptError, Unit]] =
  (for
    connected <- Proc.status(onTarget("true"), root).liftTScriptError
    _ <- failUnless(connected == 0)(show"could not connect to $target. …")
    _ <- machineFiles.traverse_(copyMachineFile(_).toEitherT)
    rootAccess <- becomeRoot.memoize.liftTScriptError
    hasPlasma <- IO.blocking(os.isDir(hostDir / "plasma")).liftTScriptError
    _ <- copyPlasmaFiles(rootAccess).toEitherT.whenA(hasPlasma)
    …
  yield ()).value

// cats-mtl: plain IO; failing is a capability
def steps(using CanFail): IO[Unit] =
  for
    connected <- Proc.status(onTarget("true"), root)
    _ <- failUnless(connected == 0)(show"could not connect to $target. …")
    _ <- machineFiles.traverse_(copyMachineFile)
    rootAccess <- becomeRoot.memoize
    hasPlasma <- IO.blocking(os.isDir(hostDir / "plasma"))
    _ <- copyPlasmaFiles(rootAccess).whenA(hasPlasma)
    …
  yield ()

// cats-cps: EitherT, in direct style (.await for EitherT, .awaitIO for IO)
def steps: ScriptT[Unit] = async[ScriptT] {
  val connected = Proc.status(onTarget("true"), root).awaitIO
  failUnless(connected == 0)(show"could not connect to $target. …").await
  for file <- machineFiles.toVector do copyMachineFile(file).await
  val rootAccess = becomeRoot.value.memoize.awaitIO
  if IO.blocking(os.isDir(hostDir / "plasma")).awaitIO then copyPlasmaFiles(rootAccess).await
  …
}

// kyo: direct style; the effect row carries the errors
def steps(using Frame): Unit < Script =
  direct {
    val connected = Proc.status(onTarget("true"), root).now
    unless(connected == 0)(fail(show"could not connect to $target. …")).now
    Kyo.foreachDiscard(machineFiles)(copyMachineFile).now
    val rootAccess = Async.memoize(becomeRoot).now
    if (hostDir / "plasma").isDirectory.now then copyPlasmaFiles(rootAccess).now
    …
  }

// zio: typed error channel, ZIO's own combinators
def steps: IO[ScriptError, Unit] =
  for
    connected <- Proc.status(onTarget("true"), root)
    _ <- failUnless(connected == 0)(show"could not connect to $target. …")
    _ <- ZIO.foreachDiscard(machineFiles)(copyMachineFile)
    rootAccess <- becomeRoot.memoize
    hasPlasma <- Files.isDirectory(hostDir / "plasma")
    _ <- copyPlasmaFiles(rootAccess).when(hasPlasma)
    …
  yield ()

// zio-interop: ZIO's typed errors, cats' combinators
def steps: IO[ScriptError, Unit] =
  for
    connected <- Proc.status(onTarget("true"), root)
    _ <- failUnless(connected == 0)(show"could not connect to $target. …")
    _ <- machineFiles.traverse_(copyMachineFile(_))
    rootAccess <- becomeRoot.memoize
    hasPlasma <- blocking(os.isDir(hostDir / "plasma"))
    _ <- copyPlasmaFiles(rootAccess).whenA(hasPlasma)
    …
  yield ()

// ox: plain code; Either for errors, .ok() to unwrap or leave the block
def steps: Either[ScriptError, Unit] = either {
  val connected = Proc.status(onTarget("true"), root)
  failUnless(connected == 0)(show"could not connect to $target. …").ok()
  machineFiles.foreach(copyMachineFile(_).ok())
  lazy val rootAccess = becomeRoot
  if os.isDir(hostDir / "plasma") then copyPlasmaFiles(rootAccess).ok()
  …
}
```

### cats-effect + `EitherT`: what I'm sticking with

Sound, explicit and dependency-free, but clunky:
- **Plumbing:** 157 calls exist only to move between `IO` and `EitherT`.
- **Inference:** with three type parameters, Scala often can't work out the error type.
  The last commit before this experiment was a type-inference workaround (`.toEitherT`
  and `.pure[ScriptT]` needed spelled-out types, so back to `EitherT(…)` and
  `EitherT.pure`).
- **Two error channels:** a `Left` and an exception in `IO` are different failures. An
  IO-level `onError` or `guaranteeCase` treats a `Left` as a success.
- **Leaving and re-entering:** `Resource`, `memoize`, `timed` and `parTraverse` all speak
  `IO`, so the code keeps stepping out of `EitherT` and back. The result is shapes like
  `Resource[IO, Either[ScriptError, A]]` and `.use(_.toEitherT.flatMap(…).value)`.

Still, it's what I keep. The noise is visible and boring: every lifting call says which
layer a line is in. The errors are just values, so nothing can swallow them or let them
escape. And it needs no new library, no new runtime and no conventions a reviewer has to
remember.

### cats-effect + cats-mtl: the strongest alternative

The port was almost mechanical. Removing the plumbing turned every function that can fail
into a compile error ("no `Raise[IO, ScriptError]`"), and adding `(using CanFail)` fixed
it. The steps are plain `IO`, so every cats combinator works on them, and transformer
stacks remain available where they earn their place. It's as fast as the baseline, and
the jar barely changes.

How it works, and the price: `Handle.allow` raises your error as a private exception
inside `IO`, which only its own `rescue` catches. That leaves two hazards, which the
types don't catch and which are documented on `CanFail`:
1. **Swallowing:** a catch-all handler (`.attempt`, `.handleError(_ => …)`, `.redeem`,
   `.orElse`) around code that raises would swallow the typed error. The baseline had one
   such handler; it was narrowed to `IOException`.
2. **Escaping:** an `IO` that raises after its `allow` block has finished (a fiber
   outliving it, say) fails with an unexplained `cats.mtl.Handle$Submarine`, and your error
   value is gone.

For a CLI with one `allow` at the top this is easily safe. On a big team I'd want a lint
for catch-all handlers.

### cats-effect-cps: `EitherT` in direct style, but a fragile macro

The idea: keep `EitherT`'s sound error model and only change the syntax. `async[ScriptT]
{ … }` works for any cats `Monad`, so `EitherT[IO, ScriptError, *]` included. A
`CpsMonadConversion[IO, ScriptT]` plus a small `.awaitIO` extension lets plain `IO`s be
awaited without lifting. Then `if`, `match` and `for` loops over awaited values are
ordinary Scala, and `ifM`, `whenA` and `(for … yield …).value` disappear.

What it costs:
- **It doesn't compile on current Scala.** cats-effect-cps 0.5.0 (on dotty-cps-async
  0.9.21, and 0.9.23 too) fails on **Scala 3.9.0 and 3.10.1**, even for the README example
  `async[IO] { IO(1).await + IO(2).await }`, with a type mismatch inside its macro
  (`InferAsyncArg`). It works up to **3.8.4**, so this variant is pinned there
  (reported: [cats-effect-direct#321](https://github.com/typelevel/cats-effect-direct/issues/321);
  cats-effect-cps has since been renamed cats-effect-direct). A macro-based syntax layer is
  a bet that it keeps up with every compiler release.
- **Macro quirks you need to learn:**
  - `x.await.nonEmpty` and `!x.awaitIO` mistype with `EitherT` (not with `IO`): bind to
    a `val` first (4 places).
    [dotty-cps-async#144](https://github.com/dotty-cps-async/dotty-cps-async/issues/144)
  - Looping over a `Seq` with an `await` inside is ambiguous (two built-in instances
    match), but a `List` or `Vector` works (3 `.toVector`s).
    [dotty-cps-async#142](https://github.com/dotty-cps-async/dotty-cps-async/issues/142)
  - The expansion trips `-Wunused:explicits` ("unused explicit parameter" on every
    `async`), like kyo-direct's.
    [dotty-cps-async#143](https://github.com/dotty-cps-async/dotty-cps-async/issues/143)
  - An `await` inside a `Resource.use` lambda needs its own nested `async`.
- **No less plumbing, just different:** 207 `.await`/`.awaitIO` against `EitherT`'s 157
  lifting calls. What it removes is nesting and the combinator vocabulary for control flow.
- **Slower compiles:** about 1 s more per script than the baseline.

In short: it reads better than `EitherT` for-comprehensions, but it ties you to a macro
that broke on the current compiler. I wouldn't rely on it until that's fixed.

### Ox: no effect runtime: it held up, but effects don't show in types

The most different approach, and the most surprising result:
- **No effect type at all.** Steps are plain methods that block (on virtual threads). Errors
  are `Either[ScriptError, A]` written as `either { … step.ok() … }` (Scala 3's
  `boundary`/`break` underneath).
- **Everything just works:**
  - Resources tie to a scope (`useInScope`, `releaseAfterScope`).
  - Parallel tests are `par(tests.map(t => () => runOne(t)))`.
  - The memoized password prompt is a `lazy val`.
  - Timing is a five-line helper.
  - `OxApp` handles Ctrl-C by interrupting the main fork and running the finalizers.
- **The best numbers:** the shortest code (1283 lines), the fastest startup (0.089 s), the
  fastest check-secrets run (0.268 s), and the least memory (75 MB).
- **decline, os-lib and upickle** work unchanged, since they never needed an effect type.

What to know:
- **Exit code on Ctrl-C:** `OxApp` exits with **0** by default
  (`interruptedExitCode = ExitCode.Success`), so `ScriptApp` overrides it to 130.
- **`.ok()` is a `break`, which is an exception.** A catch-all (`catch NonFatal`, `Try`)
  around it swallows it, the same hazard cats-mtl has. `.ok()` also can't cross into
  another fork's thread. The one catch-all that existed (`secret-tool` failing to start)
  was narrowed to `IOException`.
- **Interruption is only noticed at blocking calls.** With cats-effect, cancellation stops
  the program between any two steps. With Ox, a step that finished just before Ctrl-C
  still runs the plain code after it. Under `timeout -s INT`, ssh exited first, and the
  script printed "could not connect" before exiting 130. Here that's cosmetic; for code
  with side effects between blocking calls it could matter.
- **You lose** referential transparency and the Monad combinator vocabulary. For
  short-lived scripts that mostly spawn processes and wait, I didn't miss them.
- **Nothing in a signature says a function has effects. This is the deal-breaker for me.**
  `def isBuilt(…): Boolean` runs `nix`; `def ok(args: String*): Boolean` runs `git`. The
  types only show:
  - errors (`Either[ScriptError, A]`, with `.ok()` marking where things can fail);
  - concurrency scope (`using Ox`, needed to fork or register cleanup, but not to run a
    process, read a file or print).

  So you can't tell a pure helper from one that ssh's into a machine, and refactoring
  can't rely on the difference. In the other six variants, `IO`/`ZIO`/`< Effects` in the
  return type is exactly that signal. A marker capability (`using Effects`) would only be a
  convention, since nothing stops unmarked code from calling `os.proc`. Scala 3's
  experimental capture checking aims to make direct-style effects show up in types, but
  libraries like os-lib aren't annotated for it yet.

### ZIO, native stack: the cleanest effectful code

- **Code:** typed errors without transformers, and plain for-comprehensions. Among the
  effect systems, the scripts came out shortest (deploy.scala 13% shorter than the
  baseline).
- **Cleanup:** `ZIO.acquireRelease`/`ZIO.scoped` handle resources, and zio-nio's
  `createTempDirectoryScoped` replaces a hand-written acquire/release pair.
- **Ctrl-C:** works out of the box. `ZIOAppDefault`'s shutdown hook interrupts the program
  and waits for finalizers.
- **Build:** the fastest compiles and the smallest jar.

The edges:
- **Exit codes:** a successful `ZIOApp` always exits 0, whatever `run` returns, so
  `ScriptApp` ends with `.flatMap(exit)`.
- **Name clash:** `zio.Exit` (a fiber's result) clashes with a `Exit(code)` error case
  under `import zio.*`; ours became `Halt`.
- **zio-cli** is the weakest part:
  - its runner prints a FIGlet ASCII-art banner with `--help`, sends parse errors to
    stdout and ignores leftover arguments, so the scripts call `Command.parse` directly;
  - every option gets hard-coded filler in the help ("A true or false value.") with no
    way to turn it off;
  - there are no environment-variable options;
  - `orElse` of boolean flags can't express mode alternatives (a flag always parses);
  - `-x` is taken as a positional argument rather than an unknown option;
  - it's case-insensitive by default.
- **zio-process** has no "inherit" default (stdout/stderr are piped unless you say
  otherwise), and killing a process needs `process.execute(_.destroy())`.
- **zio-nio** is a thin java.nio wrapper. Its IOExceptions are typed, so each call needs
  `.orDie` (26 of them). Its last release was in 2023, and it pulls an old zio-streams
  that has to be pinned.
- **`show"..."`:** ZIO has no `Show`; a 10-line type class does the job.

### ZIO with cats interop: works, but the worst of both

zio-interop-cats does give typed-error ZIO cats' type classes: `Monad`, `MonadError`,
`Parallel` and `Bifunctor` for **any** error type, not only `Throwable`. So `traverse_`,
`whenA`, `ifM`, `parTraverse` and `.pure[ScriptIO]` all work on `IO[ScriptError, A]`.
That much I expected. What I didn't:
- **cats' `F` is invariant, ZIO is covariant.** `uioCondition.ifM(branchThatCanFail, …)`
  doesn't compile, because `F` is fixed to `ZIO[Any, Nothing, _]`. That took 4
  ascriptions (`cond: IO[ScriptError, Boolean]`) or conditions typed as failing although
  they can't fail.
- **`handleError` on a `UIO` compiles and does nothing.** The baseline caught a failure to
  start `secret-tool` with `.handleError(_ => 1)`. Under ZIO a failed start is a defect,
  and cats' `handleError` on an error type of `Nothing` catches nothing, with no warning.
  ZIO's `catchAllDefect` is what's needed.
- **Size:** the interop brings cats-effect (and cats-mtl) along, making this the biggest
  jar at 39 MB.

If you go ZIO, go native.

### kyo: the nicest syntax, the roughest edges (1.0.0-RC7)

- **Syntax:** direct style (`direct { … .now }`) reads like ordinary code. The effect row
  (`Async & Abort[Stop] & PathWrite`) is genuinely expressive.
- **Release candidate:** it shows. I hit several bugs and gaps while porting and reported
  them (below). The workarounds are in the code:
  - **Ctrl-C:** kyo-case-app's runner exits without waiting for finalizers, so
    `ScriptApp` replaces the signal handlers and runs the program in its own fiber. That's
    about 50 lines, the subtlest code in any variant.
  - **`show"..."`:** kyo's `Render` has a `toString` catch-all, so a compile-time error
    for unrendered values needed a macro.
  - **Compiler settings:** `-Wunused` can't be `all` (kyo-direct's expansion triggers
    false warnings), and two `-Wconf`s silence warnings from kyo's inlined code.
- **Cost:** the slowest compiles (35–40% over cats-effect), the most memory on check-secrets,
  a bigger jar, and the slowest real runs (+60–90 ms).
- **CLI:** case-app only parses options, so positional arguments and env vars are parsed
  by hand.

#### Kyo issues reported during this work

All were filed while porting (2026-11-11). Each has a reproduction and, where we found
one, the workaround used here:

- [#2233](https://github.com/getkyo/kyo/issues/2233) `render"..."` doesn't process escapes in the first literal part
- [#2234](https://github.com/getkyo/kyo/issues/2234) KyoApp exits on SIGINT/SIGTERM without waiting for the program's finalizers
- [#2235](https://github.com/getkyo/kyo/issues/2235) kyo-direct: false `-Wunused` warnings (unused explicit parameter, unused pattern variable, unused import in Direct.scala)
- [#2236](https://github.com/getkyo/kyo/issues/2236) "@nowarn annotation does not suppress any warnings" from inlined `Abort.recover` / `Fiber` / `AtomicRef` under `-Wunused:all -Werror`
- [#2237](https://github.com/getkyo/kyo/issues/2237) kyo-direct: `ExprCastException` macro crash on a chained `.now` (`make.now.next.now`)
- [#2238](https://github.com/getkyo/kyo/issues/2238) feature: KyoApp/KyoCaseApp: choose the process exit code
- [#2239](https://github.com/getkyo/kyo/issues/2239) kyo-system: interrupting `Command.waitFor`/`text`/`waitForSuccess` leaves the process running
- [#2240](https://github.com/getkyo/kyo/issues/2240) feature: `Render`: a way to opt out of the `toString` catch-all instance
- [#2241](https://github.com/getkyo/kyo/issues/2241) false "match may not be exhaustive" when matching on `Maybe.filter`/`map`/`flatMap`'s result

And comments with data from this project on two existing issues:
- [#1991](https://github.com/getkyo/kyo/issues/1991) (Scope: no backpressure on abnormal exit): in a CLI, the late release becomes a lost one; a temporary directory was left behind on every Ctrl-C. The workaround is in `kyo/scala-lib/Script.scala`.
- [#2206](https://github.com/getkyo/kyo/issues/2206) (JMH dependency of kyo-kernel): JMH, jopt-simple, commons-math3 and ASM are 1,847 of the assembly jar's ~14,000 entries, about 9% of its uncompressed size.

### Macro-based direct style has a pattern

Three of the variants use, or were tried with, a macro that rewrites direct-style code:
kyo-direct, dotty-cps-async (cats-effect-cps), and zio-direct (tried, not included). All
three:
- trip `-Wunused` with false warnings from their expansion;
- have edge cases where ordinary-looking code (a chained call, a method on an awaited
  value) crashes or mistypes;
- tie you to how well they keep up with the compiler (dotty-cps-async breaks on 3.9;
  zio-direct's last release is from February 2023).

Ox gets the same "plain code" feel with no macro at all, just blocking calls on virtual
threads and `boundary`/`break`.

### The rest of the stack matters as much as the effect system

- **CLI:**
  - **decline:** by far the best fit (env-var options, real mode alternatives, unknown
    options rejected).
  - **case-app:** options only.
  - **zio-cli:** usable behind a wrapper (see above).
- **Processes and files:**
  - **os-lib:** the nicest API, and it works the same with or without an effect system.
  - **kyo-system:** good.
  - **zio-process/zio-nio:** adequate.
  - os-lib's `spawn` registers a JVM shutdown hook per process by default
    (`destroyOnExit`). Spawning from a finalizer while the JVM shuts down (an
    `ssh -O exit` cleanup on Ctrl-C) then throws "Shutdown in progress". Since our
    resource already kills the process, `destroyOnExit = false` fixes it. This was a bug
    in the original scripts; the fix is in every os-lib variant. Reported as
    [os-lib#456](https://github.com/com-lihaoyi/os-lib/issues/456), with a fix in
    [os-lib#457](https://github.com/com-lihaoyi/os-lib/pull/457).
- **JSON:** upickle, kyo-schema-json and zio-json are all fine for this.

## Verdict

| | cats-effect + EitherT | cats-mtl | cats-cps | kyo | zio | zio-interop | ox |
|---|---|---|---|---|---|---|---|
| script readability | ★★☆ | ★★★ | ★★★ | ★★★ | ★★★ | ★★☆ | ★★★ |
| typed errors | sound, noisy | by convention (no catch-alls, no escaping) | sound | sound | sound | sound, but invariance traps | by convention (no catch-alls around `.ok()`) |
| Ctrl-C and cleanup | built in | built in | built in | ~50 lines of workaround | built in (+ `exit` for codes) | built in | built in (+ exit code 130 by hand) |
| surrounding libraries | best | best | best | OK | weak CLI, thin file/process libs | best | best |
| compile / jar | medium | medium | slower / medium | slowest / bigger | fastest / smallest | medium / biggest | fast / small |
| startup / memory | medium | medium | medium | slowest / most | medium | medium | **fastest / least** |
| effects in signatures | yes (`IO`) | yes (`IO`) | yes (`EitherT[IO, …]`) | yes (`< S`) | yes (`ZIO`) | yes (`ZIO`) | **no** (only errors, as `Either`) |
| maturity | stable | stable | **broken on Scala ≥ 3.9** | RC | stable (zio-nio stale) | stable | stable |

- **My choice: cats-effect + `EitherT`.** It keeps the best surrounding libraries
  (decline, os-lib), every Monad combinator, and typed errors that are plain values, so
  nothing can swallow or leak them. I'll take the plumbing for that.
- **If the plumbing bothers you more than it bothers me: cats-effect + cats-mtl.** It
  keeps all of that and drops the plumbing. In exchange, typed errors become hidden
  exceptions in `IO`, safe as long as no catch-all handler wraps raising code.
- **Ox has the best numbers, but I won't use it.** It's the least code, the fastest, and
  the same libraries, with no effect system at all. But nothing in a type says a function
  has effects, and that's the main thing I want from an effect system; it's why I stay
  on `IO` despite `EitherT`'s plumbing. Its other trade-offs are `.ok()`'s catch-all
  hazard and interruption only at blocking calls. If effect tracking doesn't matter to
  you, it's a serious contender for CLI scripts like these.
- **If you want the strongest guarantees with the least code in an effect system:
  native ZIO.** Expect to wrap zio-cli and to use thinner file and process libraries.
- **Avoid for now:**
  - **cats-effect-cps:** a nice idea that doesn't compile on current Scala.
  - **ZIO with cats syntax:** it runs into invariance traps and produces the largest jar.
  - **kyo:** the most interesting design and the nicest-reading code, but for something
    you maintain, I'd wait for 1.0.

## How this was made

The rewrites, the checks and this README were done with
[Claude Code](https://claude.com/claude-code) (Claude Opus 5.5) in November 2026, under my
direction, working on my NixOS configuration repository. Each variant was a branch there;
this repository is a snapshot of the scripts from each.
