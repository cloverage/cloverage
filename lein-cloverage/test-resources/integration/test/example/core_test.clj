(ns example.core-test
  (:require [clojure.test :refer [deftest is]]
            [example.core :as core]))

(deftest greeting-test
  (is (= "Hello, Ada" (core/greeting "Ada"))))
