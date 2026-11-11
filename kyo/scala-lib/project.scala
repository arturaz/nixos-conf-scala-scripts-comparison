// The build settings every script shares: Scala version, dependencies, compiler
// options. Kept in this one file (scala-cli warns when directives are spread over
// several); a script pulls it in, with the rest of scala-lib/, by `//> using file scala-lib`.
//> using scala 3.9.0
//> using jvm 27
// Scala 3's lazy vals use sun.misc.Unsafe, which JDK 24+ warns about on every run.
//> using javaOpt --sun-misc-unsafe-memory-access=allow
//> using dep io.github.arturaz::yantl:0.4.3
// kyo: effects (core), direct syntax (direct), processes and files (system), JSON
// (schema-json), command lines (case-app, on case-app's parser).
//> using dep io.getkyo::kyo-core:1.0.0-RC7
//> using dep io.getkyo::kyo-direct:1.0.0-RC7
//> using dep io.getkyo::kyo-system:1.0.0-RC7
//> using dep io.getkyo::kyo-schema-json:1.0.0-RC7
//> using dep io.getkyo::kyo-case-app:1.0.0-RC7
// -Wunused without explicits and patvars: kyo-direct's expansion of `direct { ... }`
// warns about parameters and tuple patterns that are used. The -Wconf's: kyo's inline
// methods carry @nowarn annotations which, inlined into our code, warn ("@nowarn
// annotation does not suppress any warnings", with no position); kyo-direct's own
// source, inlined, has an unused import.
// -Wnonunit-statement and -Wvalue-discard: a value computed and dropped, e.g. an effect built
// but never sequenced (so never run), fails the build; deliberate discards say `: Unit`,
// or `val _ =` inside `direct { }` (kyo-direct's rewrite loses a `: Unit` ascription).
//> using options -deprecation -feature -Wunused:imports,privates,locals,implicits,nowarn -Wnonunit-statement -Wvalue-discard -Werror "-Wconf:msg=@nowarn annotation does not suppress any warnings:s" "-Wconf:src=kyo/Direct\.scala:s"
