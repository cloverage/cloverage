(ns cloverage.kahn-test
  (:require [clojure.test :as t]
            [cloverage.kahn :as k]))

(def acyclic-g
  {7 #{11 8}
   5 #{11}
   3 #{8 10}
   11 #{2 9}
   8 #{9}})

(def cyclic-g
  {7 #{11 8}
   5 #{11}
   3 #{8 10}
   11 #{2 9}
   8 #{9}
   2 #{11}}) ;oops, a cycle!


(t/deftest test-sorting
  (t/is (= [7 3 5 11 2 10 8 9] (k/kahn-sort acyclic-g)))
  (t/is (nil? (k/kahn-sort cyclic-g))))
