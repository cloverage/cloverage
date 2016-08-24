({:text "(ns cloverage.sample.dummy-sample",
  :line 1,
  :lib cloverage.sample.dummy-sample,
  :file "cloverage/sample/dummy_sample.clj",
  :form
  (ns
   cloverage.sample.dummy-sample
   "This namespace is necessary for redundancy.\n  It allows us to check whether regexs in combination with path parameters work."),
  :full-form
  (ns
   cloverage.sample.dummy-sample
   "This namespace is necessary for redundancy.\n  It allows us to check whether regexs in combination with path parameters work."),
  :tracked true,
  :covered true,
  :hits 1}
 {:text "  \"This namespace is necessary for redundancy.",
  :line 2,
  :lib cloverage.sample.dummy-sample,
  :file "cloverage/sample/dummy_sample.clj"}
 {:text
  "  It allows us to check whether regexs in combination with path parameters work.\")",
  :line 3,
  :lib cloverage.sample.dummy-sample,
  :file "cloverage/sample/dummy_sample.clj"}
 {:text "",
  :line 4,
  :lib cloverage.sample.dummy-sample,
  :file "cloverage/sample/dummy_sample.clj"}
 {:text "(defn dummy-function",
  :line 5,
  :lib cloverage.sample.dummy-sample,
  :file "cloverage/sample/dummy_sample.clj",
  :form
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
  :covered true,
  :hits 1}
 {:text "  [& args]",
  :line 6,
  :lib cloverage.sample.dummy-sample,
  :file "cloverage/sample/dummy_sample.clj"}
 {:text "  (println \"Hello, World!\"))",
  :line 7,
  :lib cloverage.sample.dummy-sample,
  :file "cloverage/sample/dummy_sample.clj",
  :form (println "Hello, World!"),
  :full-form
  ((cloverage.instrument/wrapm
    cloverage.coverage/track-coverage
    7
    println)
   (cloverage.instrument/wrapm
    cloverage.coverage/track-coverage
    7
    "Hello, World!")),
  :tracked true}
 {:text "  (println \"Hello, World!\"))",
  :line 7,
  :lib cloverage.sample.dummy-sample,
  :file "cloverage/sample/dummy_sample.clj",
  :form println,
  :full-form println,
  :tracked true}
 {:text "  (println \"Hello, World!\"))",
  :line 7,
  :lib cloverage.sample.dummy-sample,
  :file "cloverage/sample/dummy_sample.clj",
  :form "Hello, World!",
  :full-form "Hello, World!",
  :tracked true})
