(ns cloverage.source-test
  (:require [clojure.test :as t]
            [cloverage.source :as source]))

(t/deftest resource-path-test
  (t/is (= "cloverage/source_test.clj"
           (source/resource-path 'cloverage.source-test)))
  (t/is (= nil
           (source/resource-path 'namespace.that.does.not.exist))))

(t/deftest ns-form-test
  (t/is (= '(ns cloverage.source-test (:require [clojure.test :as t] [cloverage.source :as source]))
           (source/ns-form 'cloverage.source-test)))
  (t/is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Cannot read ns declaration for namespace namespace\.that\.does\.not\.exist: cannot find file"
         (source/ns-form 'namespace.that.does.not.exist))))

(t/deftest multibyte-test
  (t/testing "Form reader reads files with multibyte chars"
    (t/is (= (source/forms 'cloverage.sample.multibyte-sample)
             '((ns cloverage.sample.multibyte-sample)
               (def a "あ"))))))

(t/deftest form-reader-test
  (t/testing "Useful exception is thrown if resource-path not found for a ns"
    (with-redefs [source/resource-path (constantly nil)]
      (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Resource path not found for namespace: foo.bar"
                              (source/form-reader 'foo.bar))))))
