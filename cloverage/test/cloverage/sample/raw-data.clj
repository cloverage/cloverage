{0
 {:form
  (ns
   cloverage.sample.dummy-sample
   "This namespace is necessary for redundancy.\n  It allows us to check whether regexs in combination with path parameters work."),
  :full-form
  (ns
   cloverage.sample.dummy-sample
   "This namespace is necessary for redundancy.\n  It allows us to check whether regexs in combination with path parameters work."),
  :tracked true,
  :line 1,
  :lib cloverage.sample.dummy-sample,
  :file "cloverage/sample/dummy_sample.clj",
  :covered true,
  :hits 1},
 1
 {:form
  (def
   dummy-function
   (clojure.core/fn
    ([& args]
     (cloverage.instrument/wrapm
      cloverage.coverage/track-coverage
      5
      (println "Hello, World!"))))),
  :full-form
  (def
   dummy-function
   (clojure.core/fn
    ([& args]
     (cloverage.instrument/wrapm
      cloverage.coverage/track-coverage
      5
      (println "Hello, World!"))))),
  :tracked true,
  :line 5,
  :lib cloverage.sample.dummy-sample,
  :file "cloverage/sample/dummy_sample.clj",
  :covered true,
  :hits 1},
 2
 {:form (println "Hello, World!"),
  :full-form
  ((cloverage.instrument/wrapm
    cloverage.coverage/track-coverage
    7
    println)
   (cloverage.instrument/wrapm
    cloverage.coverage/track-coverage
    7
    "Hello, World!")),
  :tracked true,
  :line 7,
  :lib cloverage.sample.dummy-sample,
  :file "cloverage/sample/dummy_sample.clj"},
 3
 {:form println,
  :full-form println,
  :tracked true,
  :line 7,
  :lib cloverage.sample.dummy-sample,
  :file "cloverage/sample/dummy_sample.clj"},
 4
 {:form "Hello, World!",
  :full-form "Hello, World!",
  :tracked true,
  :line 7,
  :lib cloverage.sample.dummy-sample,
  :file "cloverage/sample/dummy_sample.clj"}}
