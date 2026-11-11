// The build settings every script shares: Scala version, dependencies, compiler
// options. Kept in this one file (scala-cli warns when directives are spread over
// several); a script pulls it in, with the rest of scala-lib/, by `//> using file scala-lib`.
// 3.8.4, not 3.9: cats-effect-cps' macro (dotty-cps-async 0.9.21, also 0.9.23) fails on
// Scala 3.9.0 and 3.10.1 (even `async[IO] { IO(1).await }`: a type mismatch in its
// InferAsyncArg); it works up to 3.8.4.
//> using scala 3.8.4
//> using jvm 27
// Scala 3's lazy vals use sun.misc.Unsafe, which JDK 24+ warns about on every run.
//> using javaOpt --sun-misc-unsafe-memory-access=allow
//> using dep io.github.arturaz::yantl:0.4.3
//> using dep org.typelevel::cats-effect:3.7.1
// Direct style for EitherT[IO, ...]: async/await (on dotty-cps-async).
//> using dep org.typelevel::cats-effect-cps:0.5.0
//> using dep com.lihaoyi::os-lib:0.11.10
//> using dep com.monovore::decline:2.6.2
//> using dep com.lihaoyi::upickle:4.4.3
// -Wunused without explicits and patvars: cats-effect-cps' expansion of `async[F] { ... }`
// warns "unused explicit parameter" about every async block.
// -Wnonunit-statement and -Wvalue-discard: a value computed and dropped, e.g. an effect built
// but never sequenced (so never run), fails the build; deliberate discards say `: Unit`.
//> using options -deprecation -feature -Wunused:imports,privates,locals,implicits,nowarn -Wnonunit-statement -Wvalue-discard -Werror
