(ns cloverage.coverage-test
  (:require [clojure.test :as t]
            [cloverage.coverage :as cov]
            [cloverage.instrument :as inst]
            [riddley.walk :as rw])
  (:import java.io.File))

(defn- denamespace [tree]
  "Helper function to allow backticking w/o namespace interpolation."
  (cond (seq? tree) (map denamespace tree)
        (symbol? tree) (symbol (name tree))
        :else tree))

(def sample-file
  "cloverage/sample.clj")

(defn coverage-fixture [f]
  (binding [cov/*covered*         (atom [])
            cov/*instrumented-ns* "NO_SUCH_NAMESPACE"]
    (f)))

(t/use-fixtures :each coverage-fixture)

(def output-dir  "out")

(defn expand=
  "Check that two expressions are equal modulo recursive macroexpansion."
  [f1 f2]
  (let [e1 (rw/macroexpand-all f1)
        e2 (rw/macroexpand-all f2)]
    (= e1 e2)))

(t/deftest preserves-type-hint
  (t/is (= 'long
           ;; top level type hint always worked, but ones nested in :list did not
           (-> (inst/wrap #'cov/track-coverage 0 '(prn ^long (long 0)))
               rw/macroexpand-all
               (nth 2) ; (do (cloverage/cover 0) (...)
               (nth 1) ; ((do (cloverage/cover 1) prn) (...))
               (nth 2) ; (do (cloverage/cover 2) (...))
               meta
               :tag))))

;; TODO: all test-wrap-X tests should be split in one test that checks
;; whether wrap works correctly, and one that checks track-coverage.
(t/deftest test-wrap-primitives
  (t/is (expand= `(cov/capture 0 1)       (inst/wrap #'cov/track-coverage 0 1)))
  (t/is (expand= `(cov/capture 1 "foo")   (inst/wrap #'cov/track-coverage 0 "foo")))
  (t/is (expand= `(cov/capture 2 ~'bar)   (inst/wrap #'cov/track-coverage 0 'bar)))
  (t/is (expand= `(cov/capture 3 ~'true)  (inst/wrap #'cov/track-coverage 0 'true)))
  (t/is (expand= '(1 "foo" bar true)
                 (map :form @cov/*covered*))))

(t/deftest test-wrap-vector
  (t/is (expand= `(cov/capture 0
                               [(cov/capture 1 1)
                                (cov/capture 2 "foo")
                                (cov/capture 3 ~'bar)])
                 (inst/wrap #'cov/track-coverage 0 '[1 "foo" bar]))))

(t/deftest test-wrap-map
  (let [wrapped (rw/macroexpand-all (inst/wrap #'cov/track-coverage 0 '{:a apple :b banana}))]
    (t/is (or
           (expand= `(cov/capture 0 {(cov/capture 1 :a) (cov/capture 2 ~'apple)
                                     (cov/capture 3 :b)  (cov/capture 4 ~'banana)})
                    wrapped)
           (expand= `(cov/capture 0 {(cov/capture 1 :b) (cov/capture 2 ~'banana)
                                     (cov/capture 3 :a)  (cov/capture 4 ~'apple)})
                    wrapped)))))

(t/deftest test-wrap-list
  (t/is (expand= `(cov/capture 0 ((cov/capture 1 +) (cov/capture 2 1)
                                  (cov/capture 3 2)))
                 (inst/wrap #'cov/track-coverage 0 `(+ 1 2)))))

;; XXX: the order that forms are registered is now from outside in. Bad?
(t/deftest test-wrap-fn
  (t/is (expand= `(cov/capture 0 (~(symbol "fn")
                                  [~'a] (cov/capture 1 ~'a)))
                 (inst/wrap #'cov/track-coverage 0
                            '(fn [a] a)))
        "Unnamed fn with single overload")
  (t/is (expand= `(cov/capture 2 (~(symbol "fn")
                                  ([~'a] (cov/capture 3 ~'a))
                                  ([~'a ~'b] (cov/capture 4 ~'b))))
                 (inst/wrap #'cov/track-coverage 0 '(fn ([a] a) ([a b] b))))
        "Unnamed fn with multiple overloads")
  (t/is (expand= `(cov/capture 5 (~(symbol "fn") ~'foo
                                  [~'a] (cov/capture 6 ~'a)))
                 (inst/wrap #'cov/track-coverage 0
                            '(fn foo [a] a)))
        "Named fn with single overload")
  (t/is (expand= `(cov/capture 7 (~(symbol "fn") ~'foo
                                  ([~'a] (cov/capture 8 ~'a))
                                  ([~'a ~'b] (cov/capture 9 ~'b))))
                 (inst/wrap #'cov/track-coverage 0 '(fn foo ([a] a) ([a b] b))))
        "Named fn with multiple overloads"))

(t/deftest test-wrap-def
  (t/is (expand= `(cov/capture 0 (~(symbol "def") ~'foobar))
                 (inst/wrap #'cov/track-coverage 0 '(def foobar))))
  (t/is (expand= `(cov/capture 1 (~(symbol "def") ~'foobar (cov/capture 2 1)))
                 (inst/wrap #'cov/track-coverage 0 '(def foobar 1)))))

(t/deftest test-wrap-let
  (t/is (expand= `(cov/capture 0 (~(symbol "let") []))
                 (inst/wrap #'cov/track-coverage 0 '(let []))))
  (t/is (expand= `(cov/capture 1 (~(symbol "let") [~'a (cov/capture 2 1)]))
                 (inst/wrap #'cov/track-coverage 0 '(let [a 1]))))
  (t/is (expand= `(cov/capture 3 (~(symbol "let") [~'a (cov/capture 4 1)
                                                   ~'b (cov/capture 5 2)]))
                 (inst/wrap #'cov/track-coverage 0 '(let [a 1 b 2]))))
  (t/is (expand= `(cov/capture 6 (~(symbol "let") [~'a (cov/capture 7 1)
                                                   ~'b (cov/capture 8 2)]
                                  (cov/capture 9 ~'a)))
                 (inst/wrap #'cov/track-coverage 0 '(let [a 1 b 2] a)))))

(t/deftest test-wrap-cond
  (t/is (expand= `(cov/capture 0 nil)
                 (inst/wrap #'cov/track-coverage 0 '(cond)))))

(t/deftest test-wrap-overloads
  (t/is (expand= `(([~'a] (cov/capture 0 ~'a))
                   ([~'a ~'b] (cov/capture 1 ~'a) (cov/capture 2 ~'b)))
                 (inst/wrap-overloads #'cov/track-coverage 0 '(([a] a)
                                                               ([a b] a b)))))
  (t/is (expand= `([~'a] (cov/capture 3 ~'a))
                 (inst/wrap-overloads #'cov/track-coverage 0 '([a] a)))))

(t/deftest test-wrap-for
  (t/is (not (nil? (inst/wrap #'cov/track-coverage 0 '(for [i (range 5)] i))))))

(t/deftest test-wrap-str
  (inst/wrap #'cov/track-coverage 0
             '(defn -main [& args]
                (doseq [file (file-seq ".")]
                  (println "File is" file)))))

(t/deftest test-eval-atomic
  (t/is (= 1 (eval (inst/wrap #'cov/track-coverage 0 1))))
  (t/is (= :foo (eval (inst/wrap #'cov/track-coverage 0 :foo))))
  (t/is (= "foo" (eval (inst/wrap #'cov/track-coverage 0 "foo"))))
  (t/is (= + (eval (inst/wrap #'cov/track-coverage 0 '+)))))

(t/deftest test-eval-expr
  (t/is (= 5 (eval (inst/wrap #'cov/track-coverage 0 '(+ 2 3))))))

(t/deftest test-eval-do
  (t/is (= 4 (eval (inst/wrap #'cov/track-coverage 0 '(do 4))))))

(t/deftest test-eval-ns
  (eval (inst/wrap #'cov/track-coverage 0 '(ns foo.bar)))
  (t/is (= (count (filter :tracked @cov/*covered*)) (count (filter :covered @cov/*covered*)))))

(t/deftest test-eval-case
  (doseq [x '[a b 3 #{3} fallthrough]]
    (t/is (= (str x) (eval (inst/wrap #'cov/track-coverage 0
                                      (denamespace `(case '~x
                                                      a "a"
                                                      b "b"
                                                      3 "3"
                                                      #{3} "#{3}"
                                                      "fallthrough")))))))
  (t/is (thrown? IllegalArgumentException
                 (eval (inst/wrap #'cov/track-coverage 0 (case 1))))))

;; non-local and public to easily access in eval
;; tests are not ran from the namespace they're defined in
(def ran-finally (atom nil))
(t/deftest test-eval-try
  (t/is (= :caught
           (eval (inst/wrap #'cov/track-coverage 0
                            '(try (swap! cloverage.coverage-test/ran-finally (constantly false))
                                  (throw (Exception. "try-catch test"))
                                  (catch Exception e
                                    :caught)
                                  (finally
                                    (swap! cloverage.coverage-test/ran-finally (constantly true))))))))
  (= true @ran-finally))

(defn find-form [cov form]
  (some #(and (= form (:form %)) %) cov))

(t/deftest test-instrument-gets-lines
  (inst/instrument #'cov/track-coverage
                   'cloverage.sample)
  (let [cov @cov/*covered*
        found (find-form cov '(+ 1 2))]
    #_(with-out-writer "out/foo"
        (doseq [form-info cov]
          (println form-info)))
    #_(println "Form is" )
    (t/is found)
    (t/is (:line found))
    (t/is (find-form cov '(inc (m c 0))))))

(t/deftest test-wrap-new
  (t/is (expand= `(cov/capture 0 (~'new java.io.File (cov/capture 1 "foo/bar")))
                 (inst/wrap #'cov/track-coverage 0 '(new java.io.File "foo/bar")))))

(defn- compare-colls [& colls]
  "Given N collections compares them. Returns true if collections have same
  elements (order does not matter)."
  (apply = (map frequencies colls)))

(t/deftest test-find-nses
  (t/testing "empty sequence is returned when neither paths nor regexs are provided"
    (t/is (empty? (cov/find-nses nil []))))
  (t/testing "all namespaces in a directory get returned when only path is provided"
    (t/is (compare-colls (cov/find-nses "test/cloverage/sample" [])
                         ["cloverage.sample.dummy-sample"
                          "cloverage.sample.read-eval-sample"
                          "cloverage.sample.multibyte-sample"])))
  (t/testing "only matching namespaces (from classpath) are returned when only
             regex patterns are provided:"
    (t/testing "single pattern case"
      (t/is (= (cov/find-nses nil [#"^cloverage\.sample\.read.*$"])
               ["cloverage.sample.read-eval-sample"])))
    (t/testing "multiple patterns case"
      (t/is (compare-colls (cov/find-nses nil [#"^cloverage\.sample\.read.*$"
                                               #"^cloverage\..*coverage.*$"])
                           ["cloverage.sample.read-eval-sample"
                            "cloverage.coverage-test"
                            "cloverage.coverage"]))))
  (t/testing "only matching namespaces from a directory are returned when both path
             and patterns are provided"
    (t/is (= (cov/find-nses "test/cloverage/sample" [#".*dummy.*"])
             ["cloverage.sample.dummy-sample"]))))

(t/deftest test-main
  (binding [cloverage.coverage/*exit-after-test* false]
    (t/is (=
           (cloverage.coverage/-main
            "-o" "out"
            "--text" "--html" "--raw" "--emma-xml" "--coveralls"
            "-x" "cloverage.sample"
            "cloverage.sample")
           0))))
