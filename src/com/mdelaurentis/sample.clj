(ns com.mdelaurentis.sample
  (:use [clojure test]))

(+ 1 2)

(+ (* 2 3)
   (/ 12 3))

(let [a (+ 1 2)
      b (+ 3 4)]
  (* a b))

{:a (+ 1 2) 
 (/ 4 2) "two"}

(+ 7 11)

(defn multiply [a b]
  (doall (for [i (range a)
               j (range b)]
           (+ i j)))
  (* a b))

(deftest test-multiply
  (is (not (nil? (multiply 3 4)))))

(test-multiply)



