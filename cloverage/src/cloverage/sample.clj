(ns cloverage.sample
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

(defn fully-covered [cnd]
  (if cnd (+ 1 2 3) (- 4 5 6)))

(deftest test-fully-covered
  (is (= 6 (fully-covered true)))
  (is (= -7 (fully-covered false))))

(defmulti mixed-coverage-multi type)

(defmethod mixed-coverage-multi String
  ;; fully covered
  [x]
  x)

(defmethod mixed-coverage-multi Long
  ;; partially covered
  [x]
  (if (= x 1)
    (+ x 2)
    (- x 2)))

(defmethod mixed-coverage-multi Character
  ;; not covered
  [x]
  (.toString x))

(deftest test-mixed-multi
  (is "String" (mixed-coverage-multi "String"))
  (is 3 (mixed-coverage-multi 1)))

(defmulti fully-covered-multi type)
(defmethod fully-covered-multi String [x] x)
(defmethod fully-covered-multi :default [x] x)
(deftest test-fully-covered-multi
  (is "String" (fully-covered-multi "String"))
  (is 1 (fully-covered-multi 1)))

(defn palindrome?
  "Tests whether s is a palindrom."
  ;; fully covered
  [s]
  (if-not (vector? s)
    (palindrome? (vec s))
    (if (<= (count s) 1)
      true
      (and (= (s 0) (s (dec (count s))))
           (palindrome? (subvec s 1 (dec (count s))))))))

(deftest test-palindrome
  ;; Palindrome is fully covered
  (is (palindrome? "noon"))
  (is (palindrome? "racecar"))
  (is (not (palindrome? "hello"))))

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
  ;; permutation is partially covered
  (is (not (permutation? "foo" "foobar"))))

(defn fully-covered-cond
  [n]
  (cond
    (zero? n) :zero
    :else     :nonzero))

(deftest test-fully-covered-cond
  (is (= :zero (fully-covered-cond 0)))
  (is (= :nonzero (fully-covered-cond 1))))
