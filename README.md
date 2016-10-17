cloverage
=========

Simple clojure coverage tool.

Travis: [![Build Status](https://travis-ci.org/cloverage/cloverage.svg?branch=master)](https://travis-ci.org/cloverage/cloverage)

CircleCI: [![CircleCI](https://circleci.com/gh/cloverage/cloverage.svg?style=svg)](https://circleci.com/gh/cloverage/cloverage)

## Installation

Add [![Clojars Project](http://clojars.org/lein-cloverage/latest-version.svg)](http://clojars.org/lein-cloverage) to :plugins in your .lein/profiles.clj

## Testing frameworks support

Cloverage uses clojure.test by default. If you prefer use midje, pass the `--runner :midje` flag. (In older versions of Cloverage, you had to wrap your midje tests in clojure.test's deftest. This is no longer necessary.)

## Usage

### lein
Run `lein cloverage` in your project. See cloverage/coverage.clj for more
options.

To specify the version of cloverage manually, set the `CLOVERAGE_VERSION`
to desired value, for example `CLOVERAGE_VERSION=1.0.4-SNAPSHOT lein cloverage`

By default, the plugin will use the latest release version of cloverage.

### mvn

There is no maven plugin right now. A workaround is to import this library in the
project being tested, then run:
`mvn exec:java -Dexec.classpathScope=test -Dexec.mainClass='clojure.main' -Dexec.args='--main cloverage.coverage *args-to-coverage*'`

Where *args-to-coverage* will usually be something like "-n 'ns.regex.*' -t 'text.ns.regex.*'"


## Troubleshooting

### IllegalArgumentException No matching field found: foo for class user.Bar

    IllegalArgumentException No matching field found: foo for class user.Bar  clojure.lang.Reflector.getInstanceField (Reflector.java:271)

This is usually caused by protocols with methods starting with -. Before clojure 1.6:
```
user=> (defprotocol Foo (-foo [x] x))
Foo
user=> (deftype Bar [] Foo (-foo [_] "foo"))
user.Bar
user=> (-foo (Bar.))
"foo"
user=> ((do -foo) (Bar.))

IllegalArgumentException No matching field found: foo for class user.Bar  clojure.lang.Reflector.getInstanceField (Reflector.java:271)
```

Since cloverage *will* wrap the -foo symbol to track whether it's accessed, you will get this error. Upgrade to clojure 1.6.

### Coverage reports 0% coverage after running tests

This happens if there is a namespace in your project that requires itself, for example:

```clojure
(ns foo.bar
  (:require [foo.bar :as bar]))
```

Remove the self-reference and the test coverage report should report correctly again.

## License

Distributed under the Eclipse Public License, the same as Clojure.

### Contributors

* 2015 LShift, Tom Parker
* 2012 LShift, Jacek Lach, Alexander Schmolck, Frank Shearar
* 2010 Michael Delaurentis

### Mentions

Some code was taken from
* Java IO interop (clojure-contrib/duck-streams) by Stuart Sierra (see cloverage/source.clj)
* Topological sort (https://gist.github.com/1263783) by Alan Dipert (see cloverage/kahn.clj)

