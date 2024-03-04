(ns cloverage.coverage-test
  (:require [clojure.data :as data]
            [clojure.string :as str]
            [clojure.test :as t]
            [cloverage.coverage :as cov]
            [cloverage.instrument :as inst]
            [cloverage.report :as rep]
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

(t/use-fixtures :each coverage-fixture)

(def output-dir  "out")

(defn- expand=* [msg forms]
  (let [expanded (map rw/macroexpand-all forms)
        expected (butlast expanded)
        actual   (last expanded)]
    (t/do-report
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

(defmethod t/assert-expr 'expand=
  [msg [_ & forms]]
  `(expand=* ~msg ~(vec forms)))

(defn- my-long [x]
  (long x))

(t/deftest preserves-type-hint
  (t/is (= 'long
           ;; top level type hint always worked, but ones nested in :list did not
           (-> (inst/wrap #'cov/track-coverage 0 '(prn ^long (my-long 0)))
               rw/macroexpand-all       ; (do (cover 0) ...)
               last                     ; ((do (cover 1) prn) ...)
               last                     ; (do (cover 2) ...)
               last                     ; ((do (cover 3) my-long) (do (cover 4) 0))
               meta
               :tag))))

(t/deftest propagates-fn-call-type-hint
  (t/testing "Tag info for a function call form should be included in the instrumented form (#308)"
    ;; e.g. (str "Oops") -> ^String (do ((do str) (do "Oops")))
    (let [form         `(new java.lang.IllegalArgumentException (str "No matching clause"))
          instrumented (rw/macroexpand-all (inst/wrap #'cov/track-coverage 0 form))]
      (t/is (= `(do
                  (cov/cover 0)
                  (new java.lang.IllegalArgumentException
                       (do (cov/cover 1)
                           ((do (cov/cover 2) str)
                            (do (cov/cover 3) "No matching clause")))))
               instrumented))
      (let [fn-call-form (-> instrumented last last)]
        (t/is (= `(do (cov/cover 1)
                      ((do (cov/cover 2) str)
                       (do (cov/cover 3) "No matching clause")))
                 fn-call-form))
        (t/is (= java.lang.String
                 (:tag (meta fn-call-form))))))))

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
    (t/is (expand= `(cov/capture 0 {(cov/capture 1 :a) (cov/capture 2 ~'apple)
                                    (cov/capture 3 :b)  (cov/capture 4 ~'banana)})
                   `(cov/capture 0 {(cov/capture 1 :b) (cov/capture 2 ~'banana)
                                    (cov/capture 3 :a)  (cov/capture 4 ~'apple)})
                   wrapped))))

(t/deftest test-wrap-list
  (t/is (expand= `(cov/capture 0 ((cov/capture 1 my-fn) (cov/capture 2 1) (cov/capture 3 2)))
                 (inst/wrap #'cov/track-coverage 0 `(my-fn 1 2)))))

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
  (t/is (= 4 (count (inst/wrap #'cov/track-coverage 0
                               '(defn -main [& args]
                                  (doseq [file (file-seq ".")]
                                    (println "File is" file))))))))

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
  (t/is (= (count (filter :tracked (cov/covered)))
           (count (filter :covered (cov/covered))))))

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
  (t/is (= true @ran-finally)))

(defn find-form [cov form]
  (some #(and (= form (:form %)) %) cov))

(t/deftest test-instrument-gets-lines
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
      (t/testing (format "Form %s (expanded to %s) should get instrumented" (pr-str form) (pr-str expanded))
        (t/is (find-form cov expanded))
        (let [found (find-form cov expanded)]
          (t/is (:line found)))))))

(defn- eval-instrumented [form]
  (let [instrumented (rw/macroexpand-all `(inst/wrapm cov/track-coverage 0 ~form))]
    (eval instrumented)))

(t/deftest test-wrap-new
  (t/is (expand= `(cov/capture 0 (~'new java.io.File (cov/capture 1 "foo/bar")))
                 (inst/wrap #'cov/track-coverage 0 '(new java.io.File "foo/bar"))))

  (t/testing "should be instrumented correctly in normal circumstances"
    (t/testing "Correct `form-type` should get detected"
      (t/is (= :new
               (inst/form-type '(new java.lang.String "ABC") nil))))
    (t/is (= "ABC"
             (eval-instrumented '(String. "ABC")))))

  (t/testing "`new` defined as a local variable"
    (t/testing "No instrumentation: local variables named `new` don't overshadow the `new` special form"
      (t/is (= "ABC"
               (let [new (partial list :new)]
                 (new String "ABC")))))
    (t/testing "With instrumentation: should behave the same as no instrumentation (#247)"
      (t/testing "Correct `form-type` should get detected"
        (t/is (= :new
                 (inst/form-type '(new java.lang.String "ABC") {'new :new}))))
      (t/is (= "ABC"
               (eval-instrumented '(let [new (partial list :new)]
                                     (String. "ABC")))))))

  (t/testing "`new` defined as a var in the current namespace"
    (binding [cov/*instrumented-ns* "cloverage.coverage-test"
              *ns*                  (the-ns 'cloverage.coverage-test)]
      (try
        (intern 'cloverage.coverage-test 'new (partial list :new))
        (t/testing "Correct `form-type` should get detected"
          (t/is (= :new
                   (inst/form-type '(new java.lang.String "ABC") nil))))
        (t/testing "No instrumentation: local def shouldn't overshadow `new` special form"
          (t/is (= "ABC"
                   (new String "ABC"))))
        (t/testing "With instrumentation: should behave the same way as no instrumentation (#247)"
          (t/is (= "ABC"
                   (eval-instrumented '(String. "ABC")))))
        (finally
          (ns-unmap (the-ns 'cloverage.coverage-test) 'new))))))

(t/deftest test-wrap-var
  (t/testing "`var` forms"
    (t/testing "should be instrumented correctly in normal circumstances"
      (t/testing "Correct `form-type` should get detected"
        (t/is (= :atomic
                 (inst/form-type '(var some?) nil))))
      (t/is (= #'some?
               (eval-instrumented '(var some?)))))

    (t/testing "`var` defined as a local variable"
      (t/testing "No instrumentation: local variables named `var` don't overshadow the `var` special form"
        (t/is (= #'some?
                 (let [var (partial list :var)]
                   (var some?)))))
      (t/testing "With instrumentation: should behave the same as no instrumentation (#247)"
        (t/testing "Correct `form-type` should get detected"
          (t/is (= :atomic
                   (inst/form-type '(var some?) {'var :var}))))
        (t/is (= #'some?
                 (eval-instrumented '(let [var (partial list :var)]
                                       (var some?)))))))

    (t/testing "`var` defined as a var in the current namespace"
      (binding [cov/*instrumented-ns* "cloverage.coverage-test"
                *ns*                  (the-ns 'cloverage.coverage-test)]
        (try
          (intern 'cloverage.coverage-test 'var (partial list :var))
          (t/testing "Correct `form-type` should get detected"
            (t/is (= :atomic
                     (inst/form-type '(var some?) nil))))
          (t/testing "No instrumentation: local def shouldn't overshadow `var` special form"
            (t/is (= #'some?
                     (var some?))))
          (t/testing "With instrumentation: should behave the same way as no instrumentation (#247)"
            (t/is (= #'some?
                     (eval-instrumented '(var some?)))))
          (finally
            (ns-unmap (the-ns 'cloverage.coverage-test) 'var)))))))

(defn- compare-colls
  "Given N collections compares them. Returns true if collections have same
  elements (order does not matter)."
  [& colls]
  (apply = (map frequencies colls)))

(t/deftest test-find-nses
  (t/testing "empty sequence is returned when neither paths nor regexs are provided"
    (t/is (empty? (cov/find-nses [] []))))
  (t/testing "all namespaces in a directory get returned when only path is provided"
    (t/is (compare-colls (cov/find-nses ["dev-resources/cloverage/sample"] [])
                         ["cloverage.sample.dummy-sample"
                          "cloverage.sample.exercise-instrumentation"
                          "cloverage.sample.read-eval-sample"
                          "cloverage.sample.multibyte-sample"])))
  (t/testing "only matching namespaces (from classpath) are returned when only
            regex patterns are provided:"
    (t/testing "single pattern case"
      (t/is (= (cov/find-nses [] [#"^cloverage\.sample\.read.*$"])
               ["cloverage.sample.read-eval-sample"])))
    (t/testing "multiple patterns case"
      (t/is (compare-colls (cov/find-nses [] [#"^cloverage\.sample\.read.*$"
                                              #"^cloverage\..*coverage.*$"])
                           ["cloverage.sample.read-eval-sample"
                            "cloverage.coverage-test"
                            "cloverage.coverage"]))))
  (t/testing "only matching namespaces from a directory are returned when both path
            and patterns are provided"
    (t/is (= (cov/find-nses ["dev-resources/cloverage/sample"] [#".*dummy.*"])
             ["cloverage.sample.dummy-sample"]))))

(t/deftest test-main
  (binding [cov/*exit-after-test* false]
    (t/is (=
           (cov/-main
            "-o" "out"
            "--junit" "--text" "--html" "--raw" "--emma-xml" "--coveralls" "--codecov" "--lcov"
            "-x" "cloverage.sample.exercise-instrumentation"
            "cloverage.sample.exercise-instrumentation")
           0))))

(defn remove-dynamic-strings
  "Removes strings that are dynamic/unpredictable, for a stable comparison"
  [s]
  (-> s
      (str/replace #"\"service_job_id\":\"\d+\""
                   "\"service_job_id\":null")
      (str/replace "\"service_name\":\"circleci\""
                   "\"service_name\":null")))

(defn- assert-equal-content! [fname dir-a dir-b]
  (t/is (= (remove-dynamic-strings (slurp (io/file dir-a fname)))
           (remove-dynamic-strings (slurp (io/file dir-b fname))))
        (str "Failing for file: " fname)))

(t/deftest test-all-reporters
  (let [generated-files ["coverage.txt" "index.html" "coverage.xml" "lcov.info" "coveralls.json"
                         #_#_#_"raw-data.clj" "raw-stats.clj" "codecov.json"]]
    (doseq [f generated-files]
      (clojure.java.io/delete-file (io/file "out" f) true))

    (binding [cov/*exit-after-test* false]
      (cov/-main
       "-o" "out"
       "--junit" "--text" "--html" "--raw" "--emma-xml" "--coveralls" "--codecov" "--lcov"
       "-x" "cloverage.sample.exercise-instrumentation"
       "cloverage.sample.exercise-instrumentation")
      (doseq [fname generated-files]
        ;; If this deftest is failing, you can temporarily enable this to update the expectations:
        #_(spit (str "test/resources/" fname) (slurp (str "out/" fname)))
        (assert-equal-content! fname "test/resources" "out")))))

(t/deftest test-cyclic-dependency
  (binding [cov/*exit-after-test* false]
    (let [orig src/ns-form]
      (with-redefs [src/ns-form (fn [ns-symbol]
                                  (if (= ns-symbol 'cloverage.sample.cyclic-dependency)
                                    '(ns cloverage.sample.cyclic-dependency
                                       (:require [cloverage.sample.cyclic-dependency :as self]))
                                    (orig ns-symbol)))]
        (t/is
         (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Circular dependency between cloverage\.sample\.cyclic-dependency and cloverage\.sample\.cyclic-dependency"
          (cov/-main
           "-o" "out"
           "--emma-xml"
           "--extra-test-ns" "cloverage.sample.cyclic-dependency"
           "cloverage.sample.cyclic-dependency")))))))

(t/deftest test-no-ns-found-for-instrumentation
  (binding [cov/*exit-after-test* false]
    (t/testing "Expect validation error when no namespaces are selected for instrumentation"
      (t/is
       (thrown-with-msg?
        RuntimeException #"No namespaces selected for instrumentation.*"
        (cov/-main
         "-o" "out"
         "--emma-xml"
         "--ns-regex" "cloverage.*"
         "--ns-exclude-regex" ".*"))))))

(t/deftest test-require-ns
  (t/is (= 'cloverage.coverage-test
           (ns-name (#'cov/require-ns "cloverage.coverage-test")))))

(t/deftest test-runner-fn-eftest
  (let [opts {:runner :eftest
              :src-ns-path ["src"]
              :test-ns-path ["test"]}]
    (t/testing "check that eftest runner-fn just runs with opts as a seq of vectors, no errors"
      (let [runner-opts {:runner-opts '([:test-warn-time 100]
                                        [:multithread? false])}]
        (with-redefs [cov/resolve-var (fn [_x] (constantly {:error 0 :fail 0}))
                      cov/find-nses (constantly [])
                      cov/require-ns (constantly "test")
                      require (constantly nil)]
          (t/is (= {:errors 0}
                   ((cov/runner-fn (merge opts runner-opts)) []))))))

    (t/testing "check that eftest runner-fn just runs with opts as a map and returns errors"
      (let [runner-opts {:runner-opts {:test-warn-time 100
                                       :multithread? false}}]
        (with-redefs [cov/resolve-var (fn [_x] (constantly {:error 1 :fail 2}))
                      cov/find-nses (constantly [])
                      cov/require-ns (constantly "test")
                      require (constantly nil)]
          (t/is (= {:errors 3}
                   ((cov/runner-fn (merge opts runner-opts)) []))))))))

(t/deftest test-coverage-under?
  (t/testing "check that the different thresholds work properly"
    (let [coverage-under? #'cov/coverage-under?
          forms           {}]
      (with-redefs [rep/total-stats (constantly {:percent-lines-covered 97
                                                 :percent-forms-covered 75})]
        (t/are [result fail-threshold line-fail-threshold form-fail-threshold]
               (= result (coverage-under? forms fail-threshold line-fail-threshold form-fail-threshold))
               #_result #_fail-threshold #_line-fail-threshold #_form-fail-threshold
               ; Non-zero fail-threshold
               true     100              0                     0                     ; line and form coverage both under fail-threshold
               true     90               0                     0                     ; line coverage is under fail-threshold, form coverage is above fail-threshold
               false    70               0                     0                     ; line and form coverage both above fail-threshold
               false    70               100                   0                     ; line-fail-threshold ignored because fail-threshold is non-zero
               false    70               0                     100                   ; form-fail-threshold ignored because fail-threshold is non-zero
               false    70               100                   100                   ; line- and form-fail-threshold ignored because fail-threshold is non-zero
               ; fail-threshold is 0
               nil      0                0                     0
               false    0                90                    70                    ; line coverage is above line-fail-threshold and form coverage is above form-fail-threshold
               true     0                100                   70                    ; line coverage is under line-fail-threshold, form coverage is above form-fail-threshold
               true     0                90                    90                    ; line coverage is above line-fail-threshold, form coverage is under form-fail-threshold
               true     0                100                   100)))))              ; line coverage is under line-fail-threshold and form coverage is under form-fail-threshold
