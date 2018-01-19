(ns cloverage.source-test
  (:require [cloverage.source :as sut]
            [clojure.test :as t]))

(t/deftest multibyte-test
  (t/testing "Form reader reads files with multibyte chars"
    (t/is (= (sut/forms 'cloverage.sample.multibyte-sample)
             '((ns cloverage.sample.multibyte-sample)
               (def a "あ"))))))

(t/deftest form-reader-test
  (t/testing "Useful exception is thrown if resource-path not found for a ns"
    (with-redefs [sut/resource-path (constantly nil)]
      (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Resource path not found for namespace: foo.bar"
                              (sut/form-reader 'foo.bar))))))
