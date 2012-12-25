# coverage

Exploring some ideas for instrumenting Clojure code and reporting on test coverage.

## Usage

A hacky way of getting coverage:
Import this library in the project being tested. For maven projects, run:
`mvn exec:java -Dexec.classpathScope=test -Dexec.mainClass='clojure.main' -Dexec.args='--main cloverage.coverage *args-to-coverage*'`

Where *args-to-coverage* will usually be something like '-o out -h -x my.ns.test-script my.ns.script'

This project is not yet in a usable state.  I hope to eventually expose the following things:
+ A core library that you can use to instrument your Clojure code, for the purposes of test coverage reporting, profiling, or whatever.  Maybe this part would be a useful addition to clojure-contrib.

+ A command-line tool that takes a list of namespaces, instruments them, runs tests, and produces text or HTML reports.  Also optionally fire up a Compojure server.

+ Leiningen plugin.

+ Maven plugin.

+ Ant tasks.
