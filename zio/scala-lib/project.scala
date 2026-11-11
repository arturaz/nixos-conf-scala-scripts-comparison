// The build settings every script shares: Scala version, dependencies, compiler
// options. Kept in this one file (scala-cli warns when directives are spread over
// several); a script pulls it in, with the rest of scala-lib/, by `//> using file scala-lib`.
//> using scala 3.9.0
//> using jvm 27
// Scala 3's lazy vals use sun.misc.Unsafe, which JDK 24+ warns about on every run.
//> using javaOpt --sun-misc-unsafe-memory-access=allow
//> using dep io.github.arturaz::yantl:0.4.3
// ZIO: effects (zio), streams (zio-streams, which zio-process and zio-nio use; pinned
// here, as zio-nio would pull an older one), processes (zio-process), files (zio-nio),
// command lines (zio-cli), JSON (zio-json).
//> using dep dev.zio::zio:2.1.27
//> using dep dev.zio::zio-streams:2.1.27
//> using dep dev.zio::zio-process:0.8.1
//> using dep dev.zio::zio-nio:2.0.2
//> using dep dev.zio::zio-cli:0.8.2
//> using dep dev.zio::zio-json:1.1.2
// -Wnonunit-statement and -Wvalue-discard: a value computed and dropped, e.g. an effect built
// but never sequenced (so never run), fails the build; deliberate discards say `: Unit`.
//> using options -deprecation -feature -Wunused:all -Wnonunit-statement -Wvalue-discard -Werror
