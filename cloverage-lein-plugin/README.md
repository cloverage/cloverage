# cloverage-lein-plugin

A Leiningen plugin to run cloverage test coverage.

## Usage


Put `[cloverage-lein-plugin "0.1.0-SNAPSHOT"]` into the `:plugins` vector of your
`:user` profile, or if you are on Leiningen 1.x do `lein plugin install
cloverage-lein-plugin 0.1.0-SNAPSHOT`.

  $ lein cloverage -o output-dir -h --test-pattern '^my.namespace\..*-tests$' \
      --pattern '^my.namespace\.((?!-tests).)*'

This example uses negative lookahead to match namespaces not ending with tests,
but if your tests are in different packages you can match more easily.

## License

Copyright © 2012 LShift

Distributed under the Eclipse Public License, the same as Clojure.
