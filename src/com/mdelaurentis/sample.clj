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

(defn not-covered-at-all
  "This function is not covered at all"
  [arg1 arg2]
  (+ 2 3)
  (- 2 3)
  )

(defn partially-covered
  [cnd]
  (if cnd (+ 1 2 3) (- 2 3 4)))

(deftest test-partially-covered
  (is (= 6 (partially-covered true))))

(defn palindrome? 
  "Tests whether s is a palindrom."
  [s]
  (if-not (vector? s)
    (palindrome? (vec s))
    (if (<= (count s) 1)
      true
      (and (= (s 0) (s (dec (count s))))
           (palindrome? (subvec s 1 (dec (count s))))))))

(defn permutation? 
  "Tests whether a and b are permutations of each other"
  [a b]
  (and (= (count a)
          (count b))
       (let [add-occurrence (fn [m c] (assoc m c (inc (m c 0))))
             a-counts (reduce add-occurrence {} a)
             b-counts (reduce add-occurrence {} b)]
         (= a-counts b-counts))))

(deftest test-permutation
  (is (not (permutation? "foo" "foobar"))))

(deftest test-palindrome 
  (is (palindrome? "noon"))
  (is (palindrome? "racecar"))
  (is (not (palindrome? "hello"))))
