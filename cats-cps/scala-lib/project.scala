// The build settings every script shares: Scala version, dependencies, compiler
// options. Kept in this one file (scala-cli warns when directives are spread over
// several); a script pulls it in, with the rest of scala-lib/, by `//> using file scala-lib`.
//> using scala 3.9.0
//> using jvm 27
// Scala 3's lazy vals use sun.misc.Unsafe, which JDK 24+ warns about on every run.
//> using javaOpt --sun-misc-unsafe-memory-access=allow
//> using dep io.github.arturaz::yantl:0.4.3
//> using dep org.typelevel::cats-effect:3.7.1
// Direct style for EitherT[IO, ...]: dotty-cps-async's async/await, with its own
// cats-effect instances (not typelevel's cats-effect-cps, stuck on dotty-cps-async 0.9.21).
//> using dep io.github.dotty-cps-async::cps-async-connect-cats-effect:1.4.0
//> using dep com.lihaoyi::os-lib:0.11.10
//> using dep com.monovore::decline:2.6.2
//> using dep com.lihaoyi::upickle:4.4.3
// -Wunused without explicits and patvars: dotty-cps-async's expansion of `async[F] { ... }`
// warns "unused explicit parameter" about every async block (still in 1.4.0,
// https://github.com/dotty-cps-async/dotty-cps-async/issues/143).
// -Wnonunit-statement and -Wvalue-discard: a value computed and dropped, e.g. an effect built
// but never sequenced (so never run), fails the build; deliberate discards say `: Unit`.
//> using options -deprecation -feature -Wunused:imports,privates,locals,implicits,nowarn -Wnonunit-statement -Wvalue-discard -Werror
