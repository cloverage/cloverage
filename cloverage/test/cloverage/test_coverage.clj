(ns cloverage.test-coverage
  (:import [java.io File])
  (:use [clojure.test :exclude [report]]
        [cloverage coverage instrument source])
  (:require [riddley.walk :refer [macroexpand-all]]))

(defn- denamespace [tree]
  "Helper function to allow backticking w/o namespace interpolation."
  (cond (seq? tree) (map denamespace tree)
        (symbol? tree) (symbol (name tree))
        :else tree))

(def sample-file
     "cloverage/sample.clj")

(defn coverage-fixture [f]
  (binding [*covered*         (atom [])
            *instrumented-ns* "NO_SUCH_NAMESPACE"]
    (f)))

(use-fixtures :each coverage-fixture)

(def output-dir  "out")

(defn expand=
  "Check that two expressions are equal modulo recursive macroexpansion."
  [f1 f2]
  (let [e1 (macroexpand-all f1)
        e2 (macroexpand-all f2)]
    (= e1 e2)))

;; TODO: all test-wrap-X tests should be split in one test that checks
;; whether wrap works correctly, and one that checks track-coverage.
(deftest test-wrap-primitives
  (is (expand= `(capture 0 1)     (wrap track-coverage 0 1)))
  (is (expand= `(capture 1 "foo")   (wrap track-coverage 0 "foo")))
  (is (expand= `(capture 2 ~'bar)   (wrap track-coverage 0 'bar)))
  (is (expand= `(capture 3 ~'true)  (wrap track-coverage 0 'true)))
  (is (expand= '(1 "foo" bar true)
               (map :form @*covered*))))

(deftest test-wrap-vector
  (is (expand= `(capture 0
                         [(capture 1 1)
                          (capture 2 "foo")
                          (capture 3 ~'bar)])
               (wrap track-coverage 0 '[1 "foo" bar]))))

(deftest test-wrap-map
  (is (expand= `(capture 0 {(capture 3 :a) (capture 4 ~'apple)
                            (capture 1 :b)  (capture 2 ~'banana)})
               (wrap track-coverage 0 '{:a apple :b banana}))))

(deftest test-wrap-list
  (is (expand= `(capture 0 ((capture 1 +) (capture 2 1)
                            (capture 3 2)))
               (wrap track-coverage 0 `(+ 1 2)))))

;; XXX: the order that forms are registered is now from outside in. Bad?
(deftest test-wrap-fn
  (is (expand= `(capture 0 (~(symbol "fn")
                                     [~'a] (capture 1 ~'a)))
               (wrap track-coverage 0
                     '(fn [a] a)))
      "Unnamed fn with single overload")
  (is (expand= `(capture 2 (~(symbol "fn")
                                     ([~'a] (capture 3 ~'a))
                                     ([~'a ~'b] (capture 4 ~'b))))
               (wrap track-coverage 0 '(fn ([a] a) ([a b] b))))
      "Unnamed fn with multiple overloads")
  (is (expand= `(capture 5 (~(symbol "fn") ~'foo
                                     [~'a] (capture 6 ~'a)))
               (wrap track-coverage 0
                     '(fn foo [a] a)))
      "Named fn with single overload")
  (is (expand= `(capture 7 (~(symbol "fn") ~'foo
                                     ([~'a] (capture 8 ~'a))
                                     ([~'a ~'b] (capture 9 ~'b))))
               (wrap track-coverage 0 '(fn foo ([a] a) ([a b] b))))
      "Named fn with multiple overloads"))

(deftest test-wrap-def
  (is (expand= `(capture 0 (~(symbol "def") ~'foobar))
               (wrap track-coverage 0 '(def foobar))))
  (is (expand= `(capture 1 (~(symbol "def") ~'foobar (capture 2 1)))
               (wrap track-coverage 0 '(def foobar 1)))))

(deftest test-wrap-let
  (is (expand= `(capture 0 (~(symbol "let") []))
               (wrap track-coverage 0 '(let []))))
  (is (expand= `(capture 1 (~(symbol "let") [~'a (capture 2 1)]))
               (wrap track-coverage 0 '(let [a 1]))))
  (is (expand= `(capture 3 (~(symbol "let") [~'a (capture 4 1)
                                             ~'b (capture 5 2)]))
               (wrap track-coverage 0 '(let [a 1 b 2]))))
  (is (expand= `(capture 6 (~(symbol "let") [~'a (capture 7 1)
                                             ~'b (capture 8 2)]
                                     (capture 9 ~'a)))
               (wrap track-coverage 0 '(let [a 1 b 2] a)))))

(deftest test-wrap-cond
  (is (expand= `(capture 0 nil)
               (wrap track-coverage 0 '(cond)))))

(deftest test-wrap-overloads
  (is (expand= `(([~'a] (capture 0 ~'a))
                 ([~'a ~'b] (capture 1 ~'a) (capture 2 ~'b)))
               (wrap-overloads track-coverage 0 '(([a] a)
                                                  ([a b] a b)))))
  (is (expand= `([~'a] (capture 3 ~'a))
               (wrap-overloads track-coverage 0 '([a] a)))))

(deftest test-wrap-for
  (is (not (nil? (wrap track-coverage 0 '(for [i (range 5)] i))))))

(deftest test-wrap-str
  (wrap track-coverage 0
        '(defn -main [& args]
           (doseq [file (file-seq ".")]
             (println "File is" file)))))

(deftest test-eval-atomic
  (is (= 1 (eval (wrap track-coverage 0 1))))
  (is (= :foo (eval (wrap track-coverage 0 :foo))))
  (is (= "foo" (eval (wrap track-coverage 0 "foo"))))
  (is (= + (eval (wrap track-coverage 0 '+)))))

(deftest test-eval-expr
  (is (= 5 (eval (wrap track-coverage 0 '(+ 2 3))))))

(deftest test-eval-do
  (is (= 4 (eval (wrap track-coverage 0 '(do 4))))))

(deftest test-eval-ns
  (eval (wrap track-coverage 0 '(ns foo.bar))))

(deftest test-eval-case
  (doseq [x '[a b 3 #{3} fallthrough]]
    (is (= (str x) (eval (wrap track-coverage 0
                               (denamespace `(case '~x
                                               a "a"
                                               b "b"
                                               3 "3"
                                               #{3} "#{3}"
                                               "fallthrough")))))))
  (is (thrown? IllegalArgumentException
               (eval (wrap track-coverage 0 (case 1))))))

;; non-local and public to easily access in eval
;; tests are not ran from the namespace they're defined in
(def ran-finally (atom nil))
(deftest test-eval-try
  (is (= :caught
         (eval (wrap track-coverage 0
               '(try (swap! cloverage.test-coverage/ran-finally (constantly false))
                     (throw (Exception. "try-catch test"))
                     (catch Exception e
                       :caught)
                     (finally
                       (swap! cloverage.test-coverage/ran-finally (constantly true))))))))
  (= true @ran-finally))

(defn find-form [cov form]
  (some #(and (= form (:form %)) %) cov))

(deftest test-instrument-gets-lines
  (instrument track-coverage
              'cloverage.sample)
  (let [cov @*covered*
        found (find-form cov '(+ 1 2))]
    #_(with-out-writer "out/foo"
        (doseq [form-info cov]
          (println form-info)))
    #_(println "Form is" )
    (is found)
    (is (:line found))
    (is (find-form cov '(inc (m c 0))))))

(deftest test-wrap-new
  (is (expand= `(capture 0 (~'new java.io.File (capture 1 "foo/bar")))
               (wrap track-coverage 0 '(new java.io.File "foo/bar")))))

(deftest test-main
  (cloverage.coverage/-main
   "-o" "out"
   "--text" "--html" "--raw" "--emma-xml"
   "-x" "cloverage.sample"
   "cloverage.sample"))
