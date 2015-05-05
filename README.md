cloverage
=========

Simple clojure coverage tool. Currently requires clojure 1.4.

[![Build Status](https://secure.travis-ci.org/lshift/cloverage.png?branch=master)](http://travis-ci.org/lshift/cloverage)

## Installation

Add [![Clojars Project](http://clojars.org/lein-cloverage/latest-version.svg)](http://clojars.org/lein-cloverage) to :plugins in your .lein/profiles.clj

## Testing frameworks support

This library currently only supports clojure.test. You can get midje to work
by wrapping facts in `deftest` declarations.

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

## Releases

In order to release to Clojars, you'll need to set `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` in your environment variables.

e.g. `CLOJARS_USERNAME=lshift CLOJARS_PASSWORD=<LShift Clojars password> lein release` in each of the cloverage and lein-cloverage folders.

## Changelog
1.0.6
- Features
  - Automatic push out of snapshot releases (#65)
  - Handle records correctly (#59)
  - Text summary of results (#50)
- Bugfixes
  - Cope with zero-namespace situations correctly (#62)

1.0.5:
- Bugfixes:
 - Work around AOT-ed inline functions not being wrappable (http://dev.clojure.org/jira/browse/CLJ-1330)

1.0.4:
- Features:
 - Minimal EMMA XML output format support.
 - [Coveralls](https://coveralls.io) output format.
 - Cloverage now exits with non-zero exit code when your tests fail
 - Total % coverage summary in index.html
- Bugfixes:
 - Better instrumentation logic is no longer confused by macro/symbol shadowing
 - Support for (:require [(namespace.prefix (suffix :as rename))]) ns forms
 - Cloverage jars no longer include all dependencies

1.0.3:
 - fix empty list crash
 - add letfn support
 - print html report URL after testing

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
