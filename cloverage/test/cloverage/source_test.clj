(ns cloverage.source-test
  (:require [cloverage.source :as sut]
            [clojure.test :as t]))

(t/deftest multibyte-test
  "Regression test for a bug where the form reader fails to read files
  containing multibyte chars."
  (t/is (= (sut/forms 'cloverage.sample.multibyte-sample)
           '((ns cloverage.sample.multibyte-sample)
             (def a "あ")))))
