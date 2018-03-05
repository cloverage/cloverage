cloverage
=========

Simple clojure coverage tool.

Travis: [![Build Status](https://travis-ci.org/cloverage/cloverage.svg?branch=master)](https://travis-ci.org/cloverage/cloverage)

CircleCI: [![CircleCI](https://circleci.com/gh/cloverage/cloverage.svg?style=shield)](https://circleci.com/gh/cloverage/cloverage)

## Installation

Add [![Clojars Project](http://clojars.org/lein-cloverage/latest-version.svg)](http://clojars.org/lein-cloverage) to :plugins in your .lein/profiles.clj

## Testing frameworks support

Cloverage uses `clojure.test` by default. If you prefer use `midje`, pass the `--runner :midje` flag. (In older versions of Cloverage, you had to wrap your midje tests in clojure.test's deftest. This is no longer necessary.) Other test libraries may ship with their own support for Cloverage external to this library; see their documentation for details.

## Usage

### lein
Run `lein cloverage` in your project. See cloverage/coverage.clj for more
options.

To specify the version of cloverage manually, set the `CLOVERAGE_VERSION`
to desired value, for example `CLOVERAGE_VERSION=1.0.4-SNAPSHOT lein cloverage`

By default, the plugin will use the latest release version of cloverage.

#### Leiningen project options
You can set project default settings for Cloverage in your
project. Command line arguments can still be used and will be merged
in. List options are merged by concatenation, for other options the
project value is used.

Available options and command-line arguments:
```
 Project           Switches                     Default          Desc
 -------           --------                     -------          ----
 :output           -o, --output                 target/coverage  Output directory.
 :text?            --no-text, --text            false            Produce a text report.
 :html?            --no-html, --html            true             Produce an HTML report.
 :emma-xml?        --no-emma-xml, --emma-xml    false            Produce an EMMA XML report. [emma.sourceforge.net]
 :lcov?            --no-lcov, --lcov            false            Produce a lcov/gcov report.
 :codecov?         --no-codecov, --codecov      false            Generate a JSON report for Codecov.io
 :coveralls?       --no-coveralls, --coveralls  false            Send a JSON report to Coveralls if on a CI server
 :junit?           --no-junit, --junit          false            Output test results as junit xml file. Supported in :clojure.test runner
 :raw?             --no-raw, --raw              false            Output raw coverage data (for debugging).
 :summary?         --no-summary, --summary      true             Prints a summary
 :fail-threshold   --fail-threshold             0                Sets the percentage threshold at which cloverage will abort the build. Default: 0%
 :low-watermark    --low-watermark              50               Sets the low watermark percentage (valid values 0..100). Default: 50%
 :high-watermark   --high-watermark             80               Sets the high watermark percentage (valid values 0..100). Default: 80%
 :debug?           -d, --no-debug, --debug      false            Output debugging information to stdout.
 :runner           -r, --runner                 :clojure.test    Specify which test runner to use. Built-in runners are `clojure.test` and `midje`.
 :nop?             --no-nop, --nop              false            Instrument with noops.
 :ns-regex         -n, --ns-regex               []               Regex for instrumented namespaces (can be repeated).
 :ns-exclude-regex -e, --ns-exclude-regex       []               Regex for namespaces not to be instrumented (can be repeated).
 :test-ns-regex    -t, --test-ns-regex          []               Regex for test namespaces (can be repeated).
 :src-ns-path      -p, --src-ns-path            []               Path (string) to directory containing source code namespaces (can be repeated).
 :test-ns-path     -s, --test-ns-path           []               Path (string) to directory containing test namespaces (can be repeated).
 :extra-test-ns    -x, --extra-test-ns          []               Additional test namespace (string) to add (can be repeated).
 :help?            -h, --no-help, --help        false            Show help.
```

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
