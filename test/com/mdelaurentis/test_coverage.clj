(ns com.mdelaurentis.test-coverage
  (:import [java.io File])
  (:use [clojure.test :exclude [report]]
        [com.mdelaurentis coverage]
        [clojure.contrib.duck-streams :only [reader]]))

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

(use-fixtures :each (fn [f]
                      (binding [*covered* (ref [])]
                        (f))))


(def output-dir  "/Users/mdelaurentis/src/clojure-test-coverage/coverage" )

#_(report output-dir
          (with-coverage ['com.mdelaurentis.sample]
            (run-tests)))

#_(html-report "/Users/mdelaurentis/src/clojure-test-coverage/coverage"
 (with-coverage ['com.mdelaurentis.sample] 
   (run-tests)))

(deftest test-form-type
  (is (= :primitive (form-type 1)))
  (is (= :primitive (form-type "foo")))
  (is (= :primitive (form-type 'bar)))
  (is (= :vector (form-type [1 2 3])))
  (is (= :list (form-type '(+ 1 2)))))

(deftest test-wrap-primitives
  (is (= `(capture 0 ~'1) (wrap 1)))
  (is (= `(capture 1 "foo") (wrap "foo")))
  (is (= `(capture 2 ~'bar)  (wrap 'bar)))
  (is (= `(capture 3 ~'true)  (wrap 'true)))
  (is (= '(1 "foo" bar true)
         (map :form @*covered*))))

(deftest test-wrap-vector
  (is (= `[(capture 0 1)
           (capture 1 "foo")
           (capture 2 ~'bar)]
         (wrap '[1 "foo" bar]))))

(deftest test-wrap-map
  (is (= `{(capture 0 :a) (capture 1 ~'apple)
           (capture 2 :b)  (capture 3 ~'banana)}
         (wrap '{:a apple :b banana}))))

(deftest test-wrap-list 
  (is (= `(capture 0 ((capture 1 +) (capture 2 1)
                      (capture 3 2)))
         (wrap `(+ 1 2)))))

(deftest test-wrap-fn
  (is (= `(capture 0 (~(symbol "fn*")
                      ([~'a] (capture 1 ~'a))))
         (expand-and-wrap
          '(fn [a] a)))
      "Unnamed fn with single overload")
  (is (= `(capture 2 (~(symbol "fn") 
                      ([~'a] (capture 3 ~'a))
                      ([~'a ~'b] (capture 4 ~'b))))
         (wrap '(fn ([a] a) ([a b] b))))
      "Unnamed fn with multiple overloads")
  (is (= `(capture 5 (~(symbol "fn*") ~'foo
                      ([~'a] (capture 6 ~'a))))
         (expand-and-wrap
          '(fn foo [a] a)))
      "Named fn with single overload")
  (is (= `(capture 7 (~(symbol "fn") ~'foo
                      ([~'a] (capture 8 ~'a))
                      ([~'a ~'b] (capture 9 ~'b))))
         (wrap '(fn foo ([a] a) ([a b] b))))
      "Named fn with multiple overloads"))

(deftest test-wrap-def
  (is (= `(capture 0 (~(symbol "def") ~'foobar))
         (wrap '(def foobar))))
  (is (= `(capture 1 (~(symbol "def") ~'foobar (capture 2 1)))
         (wrap '(def foobar 1)))))

#_(deftest test-wrap-defn
    (is (= `(capture 0 (~(symbol "def") ~'foobar
                        (capture 1 (~(symbol "fn*")
                                    ([~'a] (capture 2 ~'a))))))
           (expand-and-wrap '(defn foobar [a] a)))))

(deftest test-wrap-let
  (is (= `(capture 0 (~(symbol "let*") []))
         (expand-and-wrap '(let []))))
  (is (= `(capture 1 (~(symbol "let*") [~'a (capture 2 1)]))
         (expand-and-wrap '(let [a 1]))))
  (is (= `(capture 3 (~(symbol "let*") [~'a (capture 4 1)
                                        ~'b (capture 5 2)]))
         (expand-and-wrap '(let [a 1 b 2]))))
  (is (= `(capture 6 (~(symbol "let*") [~'a (capture 7 1)
                                        ~'b (capture 8 2)] 
                      (capture 9 ~'a)))
         (expand-and-wrap '(let [a 1 b 2] a)))))

(deftest test-wrap-cond
  (is (= `(capture 0 nil)
         (expand-and-wrap '(cond)))))

(deftest test-wrap-overload
  (is (= `([~'a] (capture 0 ~'a))
         (wrap-overload '([a] a)))))

(deftest test-wrap-overloads
  (is (= `(([~'a] (capture 0 ~'a))
           ([~'a ~'b] (capture 1 ~'a) (capture 2 ~'b)))
         (wrap-overloads '(([a] a)
                           ([a b] a b)))))
  (is (= `([~'a] (capture 3 ~'a))
         (wrap-overloads '([a] a)))))


