(ns com.mdelaurentis.test-blanket
  (:import [java.io File])
  (:use [clojure.test :exclude [report]]
        [com.mdelaurentis blanket]
        [clojure.contrib.duck-streams :only [reader]]))

(def sample-file 
     "com/mdelaurentis/sample.clj")

#_(deftest test-instrument
  (binding [*covered* (ref [])]
    (let [forms (vec (instrument 'com.mdelaurentis.sample))
          cap 'com.mdelaurentis.blanket/capture
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

(def output-dir  "/Users/mdelaurentis/src/clojure-test-coverage/blanket" )

#_(report output-dir
          (with-coverage ['com.mdelaurentis.sample]
            (run-tests)))

#_(html-report "/Users/mdelaurentis/src/clojure-test-coverage/blanket"
 (with-coverage ['com.mdelaurentis.sample] 
   (run-tests)))

(deftest test-form-type
  (is (= :primitive (form-type 1)))
  (is (= :primitive (form-type "foo")))
  (is (= :primitive (form-type 'bar)))
  (is (= :vector (form-type [1 2 3])))
  (is (= :list (form-type '(+ 1 2)))))

(deftest test-wrap-primitives
  (binding [*covered* (ref [])]
    (is (= `(capture 0 1) (wrap 1)))
    (is (= `(capture 1 "foo") (wrap "foo")))
    (is (= `(capture 2 ~'bar)  (wrap 'bar)))
    (is (= '(1 "foo" bar)
           (map :form @*covered*)))))

(deftest test-wrap-vector
  (binding [*covered* (ref [])]
    (is (= `[(capture 0 1)
             (capture 1 "foo")
             (capture 2 ~'bar)]
           (wrap '[1 "foo" bar])))))

(deftest test-wrap-map
  (binding [*covered* (ref [])]
    (is (= `{(capture 0 :a) (capture 1 ~'apple)
             (capture 2 :b)  (capture 3 ~'banana)}
           (wrap '{:a apple :b banana})))))

(deftest test-wrap-list 
  (binding [*covered* (ref [])]
    (is (= `(capture 0 ((capture 1 +) (capture 2 1)
                        (capture 3 2)))
           (wrap `(+ 1 2))))))


(deftest test-wrap-fn
  (binding [*covered* (ref [])]
    (is (= `(capture 0 (~(symbol "fn") 
                         ([~'a] (capture 1 ~'a))
                         ([~'a ~'b] (capture 2 ~'b))))
           (wrap '(fn ([a] a) ([a b] b)))))))

(defmacro with-covered [& body]
  `(binding [*covered* (ref [])]
     ~@body))

(deftest test-wrap-def
  (with-covered
    (is (= `(capture 0 (~(symbol "def") ~'foobar))
           (wrap '(def foobar))))
    (is (= `(capture 1 (~(symbol "def") ~'foobar (capture 2 1)))
           (wrap '(def foobar 1))))))

(deftest test-wrap-defn
  (with-covered
    (is (= `(capture 0 (~(symbol "def") ~'foobar
                        (capture 1 (~(symbol "fn*")
                                    ([~'a] (capture 2 ~'a))))))
           (expand-and-wrap '(defn foobar [a] a))))))

(run-tests)



