(ns com.mdelaurentis.test-blanket
  (:import [java.io File])
  (:use [clojure.test :exclude [report]]
        [com.mdelaurentis blanket]))

;; Don't move this test, because it checks the line numbers of the forms.
;; 

(def sample-file 
     "/Users/mdelaurentis/src/clojure-test-coverage/src/com/mdelaurentis/sample.clj")

(deftest test-instrument
  (let [forms (vec (instrument sample-file))
        cap 'com.mdelaurentis.blanket/capture
        file sample-file]
    (is (= (list cap file 4 '(+ 1 2)) (forms 1)))
    (is (= (list cap file 6 (list '+ (list cap file 6 '(* 2 3))
                                  (list cap file 7 '(/ 12 3)))) 
           (forms 2)))
    (is (= (list cap file 9 (list 'let ['a (list cap file 9 '(+ 1 2))
                                        'b (list cap file 10 '(+ 3 4))]
                                  (list cap file 11 '(* a b))))
           (forms 3)))
    (let [form (forms 4)]
      (is (= (list cap file 13 '(+ 1 2)) (:a form)))
      (is (= (form (list cap file 14 '(/ 4 2))) "two")))))

(run-tests)

