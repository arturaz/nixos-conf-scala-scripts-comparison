// The build settings every script shares: Scala version, dependencies, compiler
// options. Kept in this one file (scala-cli warns when directives are spread over
// several); a script pulls it in, with the rest of scala-lib/, by `//> using file scala-lib`.
//> using scala 3.9.0
//> using jvm 27
// Scala 3's lazy vals use sun.misc.Unsafe, which JDK 24+ warns about on every run.
//> using javaOpt --sun-misc-unsafe-memory-access=allow
//> using dep io.github.arturaz::yantl:0.4.3
//> using dep org.typelevel::cats-effect:3.7.1
// Typed errors on plain IO: Raise/Handle (see scala-lib/Script.scala's CanFail).
//> using dep org.typelevel::cats-mtl:1.7.0
//> using dep com.lihaoyi::os-lib:0.11.10
//> using dep com.monovore::decline:2.6.2
//> using dep com.lihaoyi::upickle:4.4.3
//> using options -deprecation -feature -Wunused:all -Werror
