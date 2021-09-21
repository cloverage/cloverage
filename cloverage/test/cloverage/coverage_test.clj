(ns cloverage.coverage-test
  (:require [clojure.data :as data]
            [clojure.test :refer [testing use-fixtures deftest is do-report assert-expr]]
            [cloverage.coverage :as cov]
            [cloverage.instrument :as inst]
            [cloverage.source :as src]
            [riddley.walk :as rw]
            [clojure.java.io :as io]))

(defn- denamespace
  "Helper function to allow backticking w/o namespace interpolation."
  [tree]
  (cond (seq? tree) (map denamespace tree)
        (symbol? tree) (symbol (name tree))
        :else tree))

(defn coverage-fixture [f]
  (binding [cov/*covered*         (atom [])
            cov/*instrumented-ns* "NO_SUCH_NAMESPACE"]
    (f)))

(use-fixtures :each coverage-fixture)

(def output-dir  "out")

(defn- expand=* [msg forms]
  (let [expanded (map rw/macroexpand-all forms)
        expected (butlast expanded)
        actual   (last expanded)]
    (do-report
     {:type     (if ((set expected) actual) :pass :fail)
      :message  msg
      :expected (if (> (count expected) 1)
                  expected
                  (first expected))
      :actual   actual
      :diffs    (when-not ((set expected) actual)
                  [[actual (if (> (count expected) 1)
                             (for [form expected]
                               (data/diff form actual))
                             (data/diff (first expected) actual))]])})))

(defmethod assert-expr 'expand=
  [msg [_ & forms]]
  `(expand=* ~msg ~(vec forms)))

(deftest preserves-type-hint
  (is (= 'long
         ;; top level type hint always worked, but ones nested in :list did not
         (-> (inst/wrap #'cov/track-coverage 0 '(prn ^long (my-long 0)))
             rw/macroexpand-all       ; (do (cover 0) ...)
             last                     ; ((do (cover 1) prn) ...)
             last                     ; (do (cover 2) ...)
             last                     ; ((do (cover 3) my-long) (do (cover 4) 0))
             meta
             :tag))))

(deftest propagates-fn-call-type-hint
  (testing "Tag info for a function call form should be included in the instrumented form (#308)"
    ;; e.g. (str "Oops") -> ^String (do ((do str) (do "Oops")))
    (let [form         `(new java.lang.IllegalArgumentException (str "No matching clause"))
          instrumented (rw/macroexpand-all (inst/wrap #'cov/track-coverage 0 form))]
      (is (= `(do
                (cov/cover 0)
                (new java.lang.IllegalArgumentException
                     (do (cov/cover 1)
                         ((do (cov/cover 2) str)
                          (do (cov/cover 3) "No matching clause")))))
             instrumented))
      (let [fn-call-form (-> instrumented last last)]
        (is (= `(do (cov/cover 1)
                    ((do (cov/cover 2) str)
                     (do (cov/cover 3) "No matching clause")))
               fn-call-form))
        (is (= java.lang.String
               (:tag (meta fn-call-form))))))))

;; TODO: all test-wrap-X tests should be split in one test that checks
;; whether wrap works correctly, and one that checks track-coverage.
(deftest test-wrap-primitives
  (is (expand= `(cov/capture 0 1)       (inst/wrap #'cov/track-coverage 0 1)))
  (is (expand= `(cov/capture 1 "foo")   (inst/wrap #'cov/track-coverage 0 "foo")))
  (is (expand= `(cov/capture 2 ~'bar)   (inst/wrap #'cov/track-coverage 0 'bar)))
  (is (expand= `(cov/capture 3 ~'true)  (inst/wrap #'cov/track-coverage 0 'true)))
  (is (expand= '(1 "foo" bar true)
               (map :form @cov/*covered*))))

(deftest test-wrap-vector
  (is (expand= `(cov/capture 0
                             [(cov/capture 1 1)
                              (cov/capture 2 "foo")
                              (cov/capture 3 ~'bar)])
               (inst/wrap #'cov/track-coverage 0 '[1 "foo" bar]))))

(deftest test-wrap-map
  (let [wrapped (rw/macroexpand-all (inst/wrap #'cov/track-coverage 0 '{:a apple :b banana}))]
    (is (expand= `(cov/capture 0 {(cov/capture 1 :a) (cov/capture 2 ~'apple)
                                  (cov/capture 3 :b)  (cov/capture 4 ~'banana)})
                 `(cov/capture 0 {(cov/capture 1 :b) (cov/capture 2 ~'banana)
                                  (cov/capture 3 :a)  (cov/capture 4 ~'apple)})
                 wrapped))))

(deftest test-wrap-list
  (is (expand= `(cov/capture 0 ((cov/capture 1 my-fn) (cov/capture 2 1) (cov/capture 3 2)))
               (inst/wrap #'cov/track-coverage 0 `(my-fn 1 2)))))

;; XXX: the order that forms are registered is now from outside in. Bad?
(deftest test-wrap-fn
  (is (expand= `(cov/capture 0 (~(symbol "fn")
                                [~'a] (cov/capture 1 ~'a)))
               (inst/wrap #'cov/track-coverage 0
                          '(fn [a] a)))
      "Unnamed fn with single overload")
  (is (expand= `(cov/capture 2 (~(symbol "fn")
                                ([~'a] (cov/capture 3 ~'a))
                                ([~'a ~'b] (cov/capture 4 ~'b))))
               (inst/wrap #'cov/track-coverage 0 '(fn ([a] a) ([a b] b))))
      "Unnamed fn with multiple overloads")
  (is (expand= `(cov/capture 5 (~(symbol "fn") ~'foo
                                [~'a] (cov/capture 6 ~'a)))
               (inst/wrap #'cov/track-coverage 0
                          '(fn foo [a] a)))
      "Named fn with single overload")
  (is (expand= `(cov/capture 7 (~(symbol "fn") ~'foo
                                ([~'a] (cov/capture 8 ~'a))
                                ([~'a ~'b] (cov/capture 9 ~'b))))
               (inst/wrap #'cov/track-coverage 0 '(fn foo ([a] a) ([a b] b))))
      "Named fn with multiple overloads"))

(deftest test-wrap-def
  (is (expand= `(cov/capture 0 (~(symbol "def") ~'foobar))
               (inst/wrap #'cov/track-coverage 0 '(def foobar))))
  (is (expand= `(cov/capture 1 (~(symbol "def") ~'foobar (cov/capture 2 1)))
               (inst/wrap #'cov/track-coverage 0 '(def foobar 1)))))

(deftest test-wrap-let
  (is (expand= `(cov/capture 0 (~(symbol "let") []))
               (inst/wrap #'cov/track-coverage 0 '(let []))))
  (is (expand= `(cov/capture 1 (~(symbol "let") [~'a (cov/capture 2 1)]))
               (inst/wrap #'cov/track-coverage 0 '(let [a 1]))))
  (is (expand= `(cov/capture 3 (~(symbol "let") [~'a (cov/capture 4 1)
                                                 ~'b (cov/capture 5 2)]))
               (inst/wrap #'cov/track-coverage 0 '(let [a 1 b 2]))))
  (is (expand= `(cov/capture 6 (~(symbol "let") [~'a (cov/capture 7 1)
                                                 ~'b (cov/capture 8 2)]
                                (cov/capture 9 ~'a)))
               (inst/wrap #'cov/track-coverage 0 '(let [a 1 b 2] a)))))

(deftest test-wrap-cond
  (is (expand= `(cov/capture 0 nil)
               (inst/wrap #'cov/track-coverage 0 '(cond)))))

(deftest test-wrap-overloads
  (is (expand= `(([~'a] (cov/capture 0 ~'a))
                 ([~'a ~'b] (cov/capture 1 ~'a) (cov/capture 2 ~'b)))
               (inst/wrap-overloads #'cov/track-coverage 0 '(([a] a)
                                                             ([a b] a b)))))
  (is (expand= `([~'a] (cov/capture 3 ~'a))
               (inst/wrap-overloads #'cov/track-coverage 0 '([a] a)))))

(deftest test-wrap-for
  (is (not (nil? (inst/wrap #'cov/track-coverage 0 '(for [i (range 5)] i))))))

(deftest test-wrap-str
  (is (= 4 (count (inst/wrap #'cov/track-coverage 0
                             '(defn -main [& args]
                                (doseq [file (file-seq ".")]
                                  (println "File is" file))))))))

(deftest test-eval-atomic
  (is (= 1 (eval (inst/wrap #'cov/track-coverage 0 1))))
  (is (= :foo (eval (inst/wrap #'cov/track-coverage 0 :foo))))
  (is (= "foo" (eval (inst/wrap #'cov/track-coverage 0 "foo"))))
  (is (= + (eval (inst/wrap #'cov/track-coverage 0 '+)))))

(deftest test-eval-expr
  (is (= 5 (eval (inst/wrap #'cov/track-coverage 0 '(+ 2 3))))))

(deftest test-eval-do
  (is (= 4 (eval (inst/wrap #'cov/track-coverage 0 '(do 4))))))

(deftest test-eval-ns
  (eval (inst/wrap #'cov/track-coverage 0 '(ns foo.bar)))
  (is (= (count (filter :tracked (cov/covered)))
         (count (filter :covered (cov/covered))))))

(deftest test-eval-case
  (doseq [x '[a b 3 #{3} fallthrough]]
    (is (= (str x) (eval (inst/wrap #'cov/track-coverage 0
                                    (denamespace `(case '~x
                                                    a "a"
                                                    b "b"
                                                    3 "3"
                                                    #{3} "#{3}"
                                                    "fallthrough")))))))
  (is (thrown? IllegalArgumentException
               (eval (inst/wrap #'cov/track-coverage 0 (case 1))))))

;; non-local and public to easily access in eval
;; tests are not ran from the namespace they're defined in
(def ran-finally (atom nil))
(deftest test-eval-try
  (is (= :caught
         (eval (inst/wrap #'cov/track-coverage 0
                          '(try (swap! cloverage.coverage-test/ran-finally (constantly false))
                                (throw (Exception. "try-catch test"))
                                (catch Exception e
                                  :caught)
                                (finally
                                  (swap! cloverage.coverage-test/ran-finally (constantly true))))))))
  (is (= true @ran-finally)))

(defn find-form [cov form]
  (some #(and (= form (:form %)) %) cov))

(deftest test-instrument-gets-lines
  (inst/instrument #'cov/track-coverage
                   'cloverage.sample.exercise-instrumentation)
  (let [cov @cov/*covered*]
    (doseq [[form expanded] '{(+ 40)      (+ 40)
                              (+ 40 2)    (. clojure.lang.Numbers (add (cloverage.instrument/wrapm
                                                                        cloverage.coverage/track-coverage 10 40)
                                                                       (cloverage.instrument/wrapm
                                                                        cloverage.coverage/track-coverage 10 2)))
                              (str 1 2 3) (str 1 2 3)
                              (inc m c 0) (. clojure.lang.Numbers (inc (cloverage.instrument/wrapm
                                                                        cloverage.coverage/track-coverage
                                                                        101
                                                                        (m c 0))))}]
      (testing (format "Form %s (expanded to %s) should get instrumented" (pr-str form) (pr-str expanded))
        (is (find-form cov expanded))
        (let [found (find-form cov expanded)]
          (is (:line found)))))))

(defn- eval-instrumented [form]
  (let [instrumented (rw/macroexpand-all `(inst/wrapm cov/track-coverage 0 ~form))]
    (eval instrumented)))

(deftest test-wrap-new
  (is (expand= `(cov/capture 0 (~'new java.io.File (cov/capture 1 "foo/bar")))
               (inst/wrap #'cov/track-coverage 0 '(new java.io.File "foo/bar"))))

  (testing "should be instrumented correctly in normal circumstances"
    (testing "Correct `form-type` should get detected"
      (is (= :new
             (inst/form-type '(new java.lang.String "ABC") nil))))
    (is (= "ABC"
           (eval-instrumented '(String. "ABC")))))

  (testing "`new` defined as a local variable"
    (testing "No instrumentation: local variables named `new` don't overshadow the `new` special form"
      (is (= "ABC"
             (let [new (partial list :new)]
               (new String "ABC")))))
    (testing "With instrumentation: should behave the same as no instrumentation (#247)"
      (testing "Correct `form-type` should get detected"
        (is (= :new
               (inst/form-type '(new java.lang.String "ABC") {'new :new}))))
      (is (= "ABC"
             (eval-instrumented '(let [new (partial list :new)]
                                   (String. "ABC")))))))

  (testing "`new` defined as a var in the current namespace"
    (binding [cov/*instrumented-ns* "cloverage.coverage-test"
              *ns*                  (the-ns 'cloverage.coverage-test)]
      (try
        (intern 'cloverage.coverage-test 'new (partial list :new))
        (testing "Correct `form-type` should get detected"
          (is (= :new
                 (inst/form-type '(new java.lang.String "ABC") nil))))
        (testing "No instrumentation: local def shouldn't overshadow `new` special form"
          (is (= "ABC"
                 (new String "ABC"))))
        (testing "With instrumentation: should behave the same way as no instrumentation (#247)"
          (is (= "ABC"
                 (eval-instrumented '(String. "ABC")))))
        (finally
          (ns-unmap (the-ns 'cloverage.coverage-test) 'new))))))

(deftest test-wrap-var
  (testing "`var` forms"
    (testing "should be instrumented correctly in normal circumstances"
      (testing "Correct `form-type` should get detected"
        (is (= :atomic
               (inst/form-type '(var some?) nil))))
      (is (= #'some?
             (eval-instrumented '(var some?)))))

    (testing "`var` defined as a local variable"
      (testing "No instrumentation: local variables named `var` don't overshadow the `var` special form"
        (is (= #'some?
               (let [var (partial list :var)]
                 (var some?)))))
      (testing "With instrumentation: should behave the same as no instrumentation (#247)"
        (testing "Correct `form-type` should get detected"
          (is (= :atomic
                 (inst/form-type '(var some?) {'var :var}))))
        (is (= #'some?
               (eval-instrumented '(let [var (partial list :var)]
                                     (var some?)))))))

    (testing "`var` defined as a var in the current namespace"
      (binding [cov/*instrumented-ns* "cloverage.coverage-test"
                *ns*                  (the-ns 'cloverage.coverage-test)]
        (try
          (intern 'cloverage.coverage-test 'var (partial list :var))
          (testing "Correct `form-type` should get detected"
            (is (= :atomic
                   (inst/form-type '(var some?) nil))))
          (testing "No instrumentation: local def shouldn't overshadow `var` special form"
            (is (= #'some?
                   (var some?))))
          (testing "With instrumentation: should behave the same way as no instrumentation (#247)"
            (is (= #'some?
                   (eval-instrumented '(var some?)))))
          (finally
            (ns-unmap (the-ns 'cloverage.coverage-test) 'var)))))))

(defn- compare-colls
  "Given N collections compares them. Returns true if collections have same
  elements (order does not matter)."
  [& colls]
  (apply = (map frequencies colls)))

(deftest test-find-nses
  (testing "empty sequence is returned when neither paths nor regexs are provided"
    (is (empty? (cov/find-nses [] []))))
  (testing "all namespaces in a directory get returned when only path is provided"
    (is (compare-colls (cov/find-nses ["dev-resources/cloverage/sample"] [])
                       ["cloverage.sample.dummy-sample"
                        "cloverage.sample.exercise-instrumentation"
                        "cloverage.sample.read-eval-sample"
                        "cloverage.sample.multibyte-sample"])))
  (testing "only matching namespaces (from classpath) are returned when only
            regex patterns are provided:"
    (testing "single pattern case"
      (is (= (cov/find-nses [] [#"^cloverage\.sample\.read.*$"])
             ["cloverage.sample.read-eval-sample"])))
    (testing "multiple patterns case"
      (is (compare-colls (cov/find-nses [] [#"^cloverage\.sample\.read.*$"
                                            #"^cloverage\..*coverage.*$"])
                         ["cloverage.sample.read-eval-sample"
                          "cloverage.coverage-test"
                          "cloverage.coverage"]))))
  (testing "only matching namespaces from a directory are returned when both path
            and patterns are provided"
    (is (= (cov/find-nses ["dev-resources/cloverage/sample"] [#".*dummy.*"])
           ["cloverage.sample.dummy-sample"]))))

(deftest test-main
  (binding [cov/*exit-after-test* false]
    (is (=
         (cov/-main
          "-o" "out"
          "--junit" "--text" "--html" "--raw" "--emma-xml" "--coveralls" "--codecov" "--lcov"
          "-x" "cloverage.sample.exercise-instrumentation"
          "cloverage.sample.exercise-instrumentation")
         0))))

(defn- equal-content? [fname dir-a dir-b]
  (= (slurp (io/file dir-a fname))
     (slurp (io/file dir-b fname))))

(deftest test-all-reporters
  (let [generated-files ["coverage.txt" "index.html" "coverage.xml" "lcov.info" "coveralls.json" "cobertura.xml"
                         #_#_#_"raw-data.clj" "raw-stats.clj" "codecov.json"]]
    (doseq [f generated-files]
      (clojure.java.io/delete-file (io/file "out" f) true))

    (binding [cov/*exit-after-test* false]
      (cov/-main
       "-o" "out"
       "--junit" "--text" "--html" "--raw" "--emma-xml" "--coveralls" "--codecov" "--lcov" "--cobertura"
       "-x" "cloverage.sample.exercise-instrumentation"
       "cloverage.sample.exercise-instrumentation")
      (doseq [fname generated-files]
        (is (equal-content? fname "out" "test/resources") (str "Failing for file: " fname))))))

(deftest test-cyclic-dependency
  (binding [cov/*exit-after-test* false]
    (let [orig src/ns-form]
      (with-redefs [src/ns-form (fn [ns-symbol]
                                  (if (= ns-symbol 'cloverage.sample.cyclic-dependency)
                                    '(ns cloverage.sample.cyclic-dependency
                                       (:require [cloverage.sample.cyclic-dependency :as self]))
                                    (orig ns-symbol)))]
        (is
         (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Circular dependency between cloverage\.sample\.cyclic-dependency and cloverage\.sample\.cyclic-dependency"
          (cov/-main
           "-o" "out"
           "--emma-xml"
           "--extra-test-ns" "cloverage.sample.cyclic-dependency"
           "cloverage.sample.cyclic-dependency")))))))

(deftest test-no-ns-found-for-instrumentation
  (binding [cov/*exit-after-test* false]
    (testing "Expect validation error when no namespaces are selected for instrumentation"
      (is
       (thrown-with-msg?
        RuntimeException #"No namespaces selected for instrumentation.*"
        (cov/-main
         "-o" "out"
         "--emma-xml"
         "--ns-regex" "cloverage.*"
         "--ns-exclude-regex" ".*"))))))

(deftest test-require-ns
  (is (= 'cloverage.coverage-test
         (ns-name (#'cov/require-ns "cloverage.coverage-test")))))

(deftest test-runner-fn-eftest
  (let [opts {:runner :eftest
              :src-ns-path ["src"]
              :test-ns-path ["test"]}]
    (testing "check that eftest runner-fn just runs with opts as a seq of vectors, no errors"
      (let [runner-opts {:runner-opts '([:test-warn-time 100]
                                        [:multithread? false])}]
        (with-redefs [cov/resolve-var (fn [_x] (constantly {:error 0 :fail 0}))
                      cov/find-nses (constantly [])
                      cov/require-ns (constantly "test")
                      require (constantly nil)]
          (is (= {:errors 0}
                 ((cov/runner-fn (merge opts runner-opts)) []))))))

    (testing "check that eftest runner-fn just runs with opts as a map and returns errors"
      (let [runner-opts {:runner-opts {:test-warn-time 100
                                       :multithread? false}}]
        (with-redefs [cov/resolve-var (fn [_x] (constantly {:error 1 :fail 2}))
                      cov/find-nses (constantly [])
                      cov/require-ns (constantly "test")
                      require (constantly nil)]
          (is (= {:errors 3}
                 ((cov/runner-fn (merge opts runner-opts)) []))))))))
