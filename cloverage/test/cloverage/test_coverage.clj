(ns cloverage.test-coverage
  (:import [java.io File])
  (:use [clojure.test :exclude [report]]
        [cloverage coverage instrument source]
        ))

(defn- denamespace [tree]
  "Helper function to allow backticking w/o namespace interpolation."
  (cond (seq? tree) (map denamespace tree)
        (symbol? tree) (symbol (name tree))
        :else tree))

(def sample-file
     "cloverage/sample.clj")

(defn coverage-fixture [f]
  (binding [*covered*         (ref [])
            *instrumented-ns* "NO_SUCH_NAMESPACE"]
    (f)))

(use-fixtures :each coverage-fixture)

(def output-dir  "out")

;; TODO: all test-wrap-X tests should be split in one test that checks
;; whether wrap works correctly, and one that checks track-coverage.
(deftest test-wrap-primitives
  (is (= `(capture 0 ~'1)     (wrap track-coverage 0 1)))
  (is (= `(capture 1 "foo")   (wrap track-coverage 0 "foo")))
  (is (= `(capture 2 ~'bar)   (wrap track-coverage 0 'bar)))
  (is (= `(capture 3 ~'true)  (wrap track-coverage 0 'true)))
  (is (= '(1 "foo" bar true)
         (map :form @*covered*))))

(deftest test-wrap-vector
  (is (= `(capture 3
                   [(capture 0 1)
                    (capture 1 "foo")
                    (capture 2 ~'bar)])
         (wrap track-coverage 0 '[1 "foo" bar]))))

(deftest test-wrap-map
  (is (= `(capture 4 {(capture 0 :a) (capture 2 ~'apple)
                      (capture 1 :b)  (capture 3 ~'banana)})
         (wrap track-coverage 0 '{:a apple :b banana}))))

(deftest test-wrap-list
  (is (= `(capture 3 ((capture 0 +) (capture 1 1)
                      (capture 2 2)))
         (wrap track-coverage 0 `(+ 1 2)))))

(deftest test-wrap-fn
  (is (= `(capture 1 (~(symbol "fn")
                      [~'a] (capture 0 ~'a)))
         (wrap track-coverage 0
          '(fn [a] a)))
      "Unnamed fn with single overload")
  (is (= `(capture 4 (~(symbol "fn")
                      ([~'a] (capture 2 ~'a))
                      ([~'a ~'b] (capture 3 ~'b))))
         (wrap track-coverage 0 '(fn ([a] a) ([a b] b))))
      "Unnamed fn with multiple overloads")
  (is (= `(capture 6 (~(symbol "fn") ~'foo
                      [~'a] (capture 5 ~'a)))
         (wrap track-coverage 0
          '(fn foo [a] a)))
      "Named fn with single overload")
  (is (= `(capture 9 (~(symbol "fn") ~'foo
                      ([~'a] (capture 7 ~'a))
                      ([~'a ~'b] (capture 8 ~'b))))
         (wrap track-coverage 0 '(fn foo ([a] a) ([a b] b))))
      "Named fn with multiple overloads"))

(deftest test-wrap-def
  (is (= `(capture 0 (~(symbol "def") ~'foobar))
         (wrap track-coverage 0 '(def foobar))))
  (is (= `(capture 2 (~(symbol "def") ~'foobar (capture 1 1)))
         (wrap track-coverage 0 '(def foobar 1)))))

(deftest test-wrap-let
  (is (= `(capture 0 (~(symbol "let") []))
         (wrap track-coverage 0 '(let []))))
  (is (= `(capture 2 (~(symbol "let") [~'a (capture 1 1)]))
         (wrap track-coverage 0 '(let [a 1]))))
  (is (= `(capture 5 (~(symbol "let") [~'a (capture 3 1)
                                        ~'b (capture 4 2)]))
         (wrap track-coverage 0 '(let [a 1 b 2]))))
  (is (= `(capture 9 (~(symbol "let") [~'a (capture 6 1)
                                        ~'b (capture 7 2)]
                      (capture 8 ~'a)))
         (wrap track-coverage 0 '(let [a 1 b 2] a)))))

(deftest test-wrap-cond
  (is (= `(capture 0 nil)
         (wrap track-coverage 0 '(cond)))))

(deftest test-wrap-overloads
  (is (= `(([~'a] (capture 0 ~'a))
           ([~'a ~'b] (capture 1 ~'a) (capture 2 ~'b)))
         (wrap-overloads track-coverage 0 '(([a] a)
                           ([a b] a b)))))
  (is (= `([~'a] (capture 3 ~'a))
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
  (is (= `(capture 1 (~'new java.io.File (capture 0 "foo/bar")))
         (wrap track-coverage 0 '(new java.io.File "foo/bar")))))

(deftest test-main
  (cloverage.coverage/-main
   "-o" "out"
   "--text" "--html" "--raw"
   "-x" "cloverage.sample"
   "cloverage.sample"))
