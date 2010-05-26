(ns com.mdelaurentis.test-coverage
  (:import [java.io File])
  (:use [clojure.test :exclude [report]]
        [com.mdelaurentis coverage instrument]
        [clojure.contrib.duck-streams :only [reader with-out-writer]]
        clojure.contrib.test-contrib.test-graph))

(def sample-file 
     "com/mdelaurentis/sample.clj")

#_(deftest test-instrument
  (binding [*covered* (ref [])]
    (let [forms (vec (instrument 'com.mdelaurentis.sample))
          cap 'com.mdelaurentis.coverage/capture
          file sample-file]
      (println "Forms are" )
      (doseq [i (range (count forms))]
        (println i " " (forms i)))
      (println "Covered is" )
      (doseq [i (range (count @*covered*))]
        (println i " " (*covered* i)))
      (is (= (list cap 1 '(+ 1 2)) (forms 1))
          "Simple function call")
      (is (= (list cap 3 (list '+ (list cap 4 '(* 2 3))
                               (list cap 5 '(/ 12 3)))) 
             (forms 2))
          "Nested function calls")
      (is (= (list cap 7 (list 'let ['a (list cap 8 '(+ 1 2))
                                          'b (list cap 9 '(+ 3 4))]
                                    (list cap 10 '(* a b))))
               (forms 3))
            "Let form - make sure we wrap vectors")
      (let [form (forms 4)]
        (is (= (list cap 12 '(+ 1 2)) (:a form))
            "Make sure we wrap map values")
        (is (= (form (list cap 13 '(/ 4 2))) "two")
              "Make sure we wrap map keys")))))

#_(deftest test-with-coverage
  (let [cov (with-coverage ['com.mdelaurentis.sample] 
              (with-out-str (run-tests)))
        file-cov (first cov)
        covered? (fn [line] (:covered? ((:content file-cov)
                                        line)))]

    ;; Make sure palindrome? is fully covered
    (is (covered? 17) "Make sure we capture defn")
    (is (covered? 20))
    (is (covered? 21))
    (is (covered? 22))
;    (is (covered? 23) "Make sure we capture primitives")
    (is (covered? 24))
    (is (covered? 25))
    
    ;; Make sure permutation? is not
    (is (covered? 30))
    (is (covered? 31))
    (is (not (covered? 32)))
    (is (not (covered? 33)))
    (is (not (covered? 34)))
    (is (not (covered? 35)))))

(defn coverage-fixture [f]
  (binding [*covered* (ref [])
            *instrumenting-file* ""]
    (f)))

(use-fixtures :each coverage-fixture)

(def output-dir  "/Users/mdelaurentis/src/clojure-test-coverage/coverage" )

#_(report output-dir
          (with-coverage ['com.mdelaurentis.sample]
            (run-tests)))

#_(html-report "/Users/mdelaurentis/src/clojure-test-coverage/coverage"
 (with-coverage ['com.mdelaurentis.sample] 
   (run-tests)))

(deftest test-form-type
  (is (= :atomic (form-type 1)))
  (is (= :atomic (form-type "foo")))
  (is (= :atomic (form-type 'bar)))
  (is (= :vector (form-type [1 2 3])))
  (is (= :list (form-type '(+ 1 2))))
  (is (= :stop (form-type 'do))))

(deftest test-wrap-primitives
  (is (= `(capture 0 ~'1) (wrap track-coverage 1)))
  (is (= `(capture 1 "foo") (wrap track-coverage "foo")))
  (is (= `(capture 2 ~'bar)  (wrap track-coverage 'bar)))
  (is (= `(capture 3 ~'true)  (wrap track-coverage 'true)))
  (is (= '(1 "foo" bar true)
         (map :form @*covered*))))

(deftest test-wrap-vector
  (is (= `[(capture 0 1)
           (capture 1 "foo")
           (capture 2 ~'bar)]
         (wrap track-coverage '[1 "foo" bar]))))

(deftest test-wrap-map
  (is (= `{(capture 0 :a) (capture 2 ~'apple)
           (capture 1 :b)  (capture 3 ~'banana)}
         (wrap track-coverage '{:a apple :b banana}))))

(deftest test-wrap-list 
  (is (= `(capture 3 ((capture 0 +) (capture 1 1)
                      (capture 2 2)))
         (wrap track-coverage `(+ 1 2)))))

(deftest test-wrap-fn
  (is (= `(capture 1 (~(symbol "fn")
                      [~'a] (capture 0 ~'a)))
         (wrap track-coverage
          '(fn [a] a)))
      "Unnamed fn with single overload")
  (is (= `(capture 4 (~(symbol "fn") 
                      ([~'a] (capture 2 ~'a))
                      ([~'a ~'b] (capture 3 ~'b))))
         (wrap track-coverage '(fn ([a] a) ([a b] b))))
      "Unnamed fn with multiple overloads")
  (is (= `(capture 6 (~(symbol "fn") ~'foo
                      [~'a] (capture 5 ~'a)))
         (wrap track-coverage
          '(fn foo [a] a)))
      "Named fn with single overload")
  (is (= `(capture 9 (~(symbol "fn") ~'foo
                      ([~'a] (capture 7 ~'a))
                      ([~'a ~'b] (capture 8 ~'b))))
         (wrap track-coverage '(fn foo ([a] a) ([a b] b))))
      "Named fn with multiple overloads"))

(deftest test-wrap-def
  (is (= `(capture 0 (~(symbol "def") ~'foobar))
         (wrap track-coverage '(def foobar))))
  (is (= `(capture 2 (~(symbol "def") ~'foobar (capture 1 1)))
         (wrap track-coverage '(def foobar 1)))))

#_(deftest test-wrap-defn
    (is (= `(capture 0 (~(symbol "def") ~'foobar
                        (capture 1 (~(symbol "fn*")
                                    ([~'a] (capture 2 ~'a))))))
           (wrap track-coverage '(defn foobar [a] a)))))

(deftest test-wrap-let
  (is (= `(capture 0 (~(symbol "let*") []))
         (wrap track-coverage '(let []))))
  (is (= `(capture 2 (~(symbol "let*") [~'a (capture 1 1)]))
         (wrap track-coverage '(let [a 1]))))
  (is (= `(capture 5 (~(symbol "let*") [~'a (capture 3 1)
                                        ~'b (capture 4 2)]))
         (wrap track-coverage '(let [a 1 b 2]))))
  (is (= `(capture 9 (~(symbol "let*") [~'a (capture 6 1)
                                        ~'b (capture 7 2)] 
                      (capture 8 ~'a)))
         (wrap track-coverage '(let [a 1 b 2] a)))))

(deftest test-wrap-cond
  (is (= `(capture 0 nil)
         (wrap track-coverage '(cond)))))

(deftest test-wrap-overload
  (is (= `([~'a] (capture 0 ~'a))
         (wrap-overload track-coverage '([a] a)))))

(deftest test-wrap-overloads 
  (is (= `(([~'a] (capture 0 ~'a))
           ([~'a ~'b] (capture 1 ~'a) (capture 2 ~'b)))
         (wrap-overloads track-coverage '(([a] a)
                           ([a b] a b)))))
  (is (= `([~'a] (capture 3 ~'a))
         (wrap-overloads track-coverage '([a] a)))))

(deftest test-wrap-for
  (is (not (nil? (wrap track-coverage '(for [i (range 5)] i))))))

(deftest test-wrap-str
  (wrap track-coverage
   '(defn -main [& args]
      (doseq [file (file-seq ".")]
        (println "File is" file)))))

(deftest test-eval-atomic
  (is (= 1 (eval (wrap track-coverage 1))))
  (is (= :foo (eval (wrap track-coverage :foo))))
  (is (= "foo" (eval (wrap track-coverage "foo"))))
  (is (= + (eval (wrap track-coverage '+)))))

(deftest test-eval-expr
  (is (= 5 (eval (wrap track-coverage '(+ 2 3))))))

(deftest test-eval-do
  (is (= 4 (eval (wrap track-coverage '(do 4))))))

(deftest test-eval-ns
  (eval (wrap track-coverage '(ns foo.bar))))

(deftest test-deftest
  #_(is (= 'foo
           (let [wrapped 
                 (wrap track-coverage
                  '(deftest test-permutation
                     (is (not (com.mdelaurentis.sample/permutation? "foo" "foobar")))))]
             (prn "Evaling " wrapped)
             (eval wrapped)))))

(defn find-form [cov form]
  (some #(and (= form (:form %)) %) cov))

(deftest test-instrument-gets-lines
  (instrument track-coverage 'com.mdelaurentis.sample)
  (let [cov @*covered*
        found (find-form cov '(+ 1 2))]
    #_(with-out-writer "/Users/mdelaurentis/foo"
        (doseq [form-info cov]
          (println form-info)))
    #_(println "Form is" )
    (is found)
    (is (:line found))
    (is (find-form cov '(inc (m c 0))))))

(comment
  (binding [*covered* (ref [])
            *instrumenting-file* ""]
    (with-out-writer "/Users/mdelaurentis/inline"
      (println (wrap track-coverage '(if))))
    1)

)

(deftest test-wrap-new 
  (is (= `(capture 1 (~'new java.io.File (capture 0 "foo/bar")))
         (wrap track-coverage '(new java.io.File "foo/bar")))))

(deftest test-main
  (com.mdelaurentis.coverage/-main
   "-o" "/Users/mdelaurentis/src/clojure-test-coverage/out" 
   "--text" "--html" "--raw"
   "clojure.contrib.graph"
   "clojure.contrib.test-contrib.test-graph"
   "clojure.contrib.seq-utils"
   "clojure.contrib.test-contrib.seq-utils-test"
;   "com.mdelaurentis.sample"
;   "clojure.set"
;   "clojure.zip"
   ;"clojure.xml"
   ))

;(map #(:test (meta %)) (vals (ns-interns (find-ns 'clojure.contrib.test-contrib.test-graph))))

