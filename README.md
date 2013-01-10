cloverage
=========

Simple clojure coverage tool. Currently requires clojure 1.4.

## Installation

$ (cd cloverage && lein install) && (cd lein-cloverage && lein install)

Then add [lein-cloverage "1.0.0-SNAPSHOT"] to :plugins in your .lein/profiles.clj

## Testing frameworks support

This library currently only supports clojure.test. You can get midje to work
by wrapping facts in `deftest` declarations.

## Usage

### lein
Run `lein cloverage` in your project. See cloverage/coverage.clj for more
options.

### mvn

There is no maven plugin right now. A workaround is to import this library in the
project being tested, then run:
`mvn exec:java -Dexec.classpathScope=test -Dexec.mainClass='clojure.main' -Dexec.args='--main cloverage.coverage *args-to-coverage*'`

Where *args-to-coverage* will usually be something like "-n 'ns.regex.*' -t 'text.ns.regex.*'"


## License

Distributed under the Eclipse Public License, the same as Clojure.

### Contributors

* 2012 LShift, Jacek Lach, Alexander Schmolck, Frank Shearar
* 2010 Michael Delaurentis

### Mentions

Some code was taken from
* Java IO interop (clojure-contrib/duck-streams) by Stuart Sierra (see cloverage/source.clj)
* Topological sort (https://gist.github.com/1263783) by Alan Dipert (see cloverage/kahn.clj)
