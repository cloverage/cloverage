(ns com.mdelaurentis.sample
  (:use [clojure test]))

(+ 7 11)

(defn multiply [a b]
  (doall (for [i (range a)
               j (range b)]
           (+ i j)))
  (* a b))

(deftest test-multiply
  (is (not (nil? (multiply 3 4)))))

(test-multiply)