(ns com.mdelaurentis.test-blanket
  (:import [java.io File])
  (:use [clojure.test :exclude [report]]
        [com.mdelaurentis blanket]))

;; Don't move this test, because it checks the line numbers of the forms.
;; 

(def sample-file 
     "/Users/mdelaurentis/src/clojure-test-coverage/src/com/mdelaurentis/sample.clj")

(deftest test-instrument
  (let [forms (vec (binding [*covered* (ref {})] (instrument sample-file)))
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
  (let [cov (with-coverage [sample-file] 
              (run-tests))
        file-cov (cov sample-file)]

    ;; Make sure palindrome? is fully covered
    (is (file-cov 17) "Make sure we capture defn")
    (is (file-cov 20))
    (is (file-cov 21))
    (is (file-cov 22))
    (is (file-cov 23) "Make sure we capture primitives")
    (is (file-cov 24))
    (is (file-cov 25))
    
    ;; Make sure permutation? is not
    (is (file-cov 30))
    (is (file-cov 31))
    (is (not (file-cov 32)))
    (is (not (file-cov 33)))
    (is (not (file-cov 34)))
    (is (not (file-cov 35)))))

(run-tests)
