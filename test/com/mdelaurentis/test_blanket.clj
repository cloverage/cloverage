(ns com.mdelaurentis.test-blanket
  (:import [java.io File])
  (:use [clojure.test :exclude [report]]
        [com.mdelaurentis blanket]
        [clojure.contrib.duck-streams :only [reader]]))

(def sample-file 
     "com/mdelaurentis/sample.clj")

(deftest test-instrument
  (let [forms (vec (binding [*covered* (ref {})] (instrument 'com.mdelaurentis.sample)))
        cap 'com.mdelaurentis.blanket/capture
        file sample-file]
    (is (= (list cap file 4 '(+ 1 2)) (forms 1))
        "Simple function call")
    (is (= (list cap file 6 (list '+ (list cap file 6 '(* 2 3))
                                  (list cap file 7 '(/ 12 3)))) 
           (forms 2))
        "Nested function calls")
    (is (= (list cap file 9 (list 'let ['a (list cap file 9 '(+ 1 2))
                                        'b (list cap file 10 '(+ 3 4))]
                                  (list cap file 11 '(* a b))))
           (forms 3))
        "Let form - make sure we wrap vectors")
    (let [form (forms 4)]
      (is (= (list cap file 13 '(+ 1 2)) (:a form))
          "Make sure we wrap map values")
      (is (= (form (list cap file 14 '(/ 4 2))) "two")
          "Make sure we wrap map keys"))))

(deftest test-with-coverage
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

(html-report "/Users/mdelaurentis/src/clojure-test-coverage/blanket"
 (with-coverage ['com.mdelaurentis.sample] 
   (run-tests)))

