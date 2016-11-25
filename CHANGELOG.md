# Cloverage Changelog

Pull requests are required to update this changelog.  Changelog entries should
mention and link to any issues or tickets involved in the change, and should
provide a short summary description of the particular changes of the patch.

Include the issue number (#xxx) which will link back to the originating issue
in github. Commentary on the change should appear as a nested, unordered list.

## 1.0.10 (WIP)
- Improvements
  - Split out reporting into separate namespaces (#165)
  - Stop using Cheshire for JSON output (fewer transitive dependencies) (#165)
- Bugfixes
  - Fix performance regression: Only call `gather-stats` once (#166)
  - Abort if cyclic dependency in namespaces detected (#122)
  - Attempt creation of output dir ahead of running with --junit flag (#167)

## 1.0.9
- Improvements
  - No more reflection warnings! (#158)
  - Colorized summary report & ability to fail build on coverage &lt; threshold (#99)
- Bugfixes
  - Passthrough _-h_ / _--help_ flag to properly show help options (#156)

## 1.0.8
- Features
  - Add [Code of Conduct](https://github.com/cloverage/cloverage/blob/master/CODE_OF_CONDUCT.md) (#128)
  - Add junit support with the `--junit` flag (#127)
- Improvements
  - Coverage within `for` comprehensions is always partial (#23)
  - Move Changelog to separate file (#144)

## 1.0.7
- Features
  - Add codecov.io support with the `--codecov` flag (#78)
  - Add lcov (e.g. coverlay) support with the `--lcov` flag (#114)
  - Support for midje as a test runner with `--runner :midje` (#64)
  - Support for cljc files (#93/#94)
- Improvements
  - Coverage fn (internal hot loop) optimization (#90)
  - Dependency upgrades, including running tests on Oracle JDK 8 (#105)
- Bugfixes
  - Fix Unicode (UTF-8) support for HTML output (#100)
  - Fix handling of multibyte characters (#108)
  - Fix HTML entity encoding bug (#55)
  - Coveralls report: fix source digest, line hit numbers (#96)

## 1.0.6
- Features
  - Option to exclude namespaces (#57/#73)
  - Improved records fixes for Compojure (#66/#69)
  - Option to specify a path to src/test namespaces (#70)
  - Automatic push out of snapshot releases (#65)
  - Handle records correctly (#59)
  - Text summary of results (#50)
- Bugfixes
  - Correct test namespaces regex usage (#67)
  - Cope with zero-namespace situations correctly (#62)

## 1.0.5:
- Bugfixes:
 - Work around AOT-ed inline functions not being wrappable (http://dev.clojure.org/jira/browse/CLJ-1330)

## 1.0.4:
- Features:
 - Minimal EMMA XML output format support.
 - [Coveralls](https://coveralls.io) output format.
 - Cloverage now exits with non-zero exit code when your tests fail
 - Total % coverage summary in index.html
- Bugfixes:
 - Better instrumentation logic is no longer confused by macro/symbol shadowing
 - Support for (:require [(namespace.prefix (suffix :as rename))]) ns forms
 - Cloverage jars no longer include all dependencies

## 1.0.3:
 - fix empty list crash
 - add letfn support
 - print html report URL after testing
