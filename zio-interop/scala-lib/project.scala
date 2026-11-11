// The build settings every script shares: Scala version, dependencies, compiler
// options. Kept in this one file (scala-cli warns when directives are spread over
// several); a script pulls it in, with the rest of scala-lib/, by `//> using file scala-lib`.
//> using scala 3.9.0
//> using jvm 27
// Scala 3's lazy vals use sun.misc.Unsafe, which JDK 24+ warns about on every run.
//> using javaOpt --sun-misc-unsafe-memory-access=allow
//> using dep io.github.arturaz::yantl:0.4.3
// ZIO instead of cats-effect; zio-interop-cats gives ZIO cats' type classes (Monad,
// MonadError, Parallel for any error type), so cats' syntax works on ZIO
// (`import zio.interop.catz.*`). It brings cats-effect along, unused by the scripts.
//> using dep dev.zio::zio:2.1.27
//> using dep dev.zio::zio-interop-cats:23.1.0.15
//> using dep com.lihaoyi::os-lib:0.11.10
//> using dep com.monovore::decline:2.6.2
//> using dep com.lihaoyi::upickle:4.4.3
// -Wnonunit-statement and -Wvalue-discard: a value computed and dropped, e.g. an IO built
// but never sequenced (so never run), fails the build; deliberate discards say `: Unit`.
//> using options -deprecation -feature -Wunused:all -Wnonunit-statement -Wvalue-discard -Werror
