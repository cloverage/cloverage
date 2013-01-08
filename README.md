cloverage
==============

Simple clojure coverage tool. Currently requires clojure 1.4.

* Installation

$ (cd cloverage && lein install) && (cd lein-cloverage && lein install)

Then add [lein-cloverage "1.0.0-SNAPSHOT"] to :plugins in your
.lein/profiles.clj

* Usage

  `lein cloverage -o output-dir --test-pattern 'regex-for-test-nses'
      -p 'regex-for-code-nses'`
