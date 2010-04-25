(ns com.mdelaurentis.test-coverage
  (:use [clojure test]
        [com.mdelaurentis.coverage]))

(deftest test-wrap
  (is (= '(capture (+ 1 2)))
      (wrap '(+ 1 2)))
  (is (= '(capture (+ (capture (* 2 3)) (capture (/ 12 3)))))
      (wrap '(+ (* 2 3) (/ 12 3))))
  (is (= '(com.mdelaurentis.coverage/capture 
           15
           (let [a (com.mdelaurentis.coverage/capture 15 (+ 1 2))
                 b (com.mdelaurentis.coverage/capture 16 (+ 3 4))]
             (com.mdelaurentis.coverage/capture 17 (* a b))))
         (wrap '(let [a (+ 1 2)
                      b (+ 3 4)]
                  (* a b)))))
  (is (= '{:a (com.mdelaurentis.coverage/capture 20 (+ 1 2)) 
           (com.mdelaurentis.coverage/capture 20 (/ 4 2)) "two"}
         (wrap '{:a (+ 1 2) (/ 4 2) "two"}))))

(run-tests)