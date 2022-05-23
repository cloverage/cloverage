(ns cloverage.args-test
  (:require [clojure.test :refer [are deftest is testing]]
            [cloverage.args :as args]
            [cloverage.coverage])
  (:import (clojure.lang ExceptionInfo)))

(deftest overwriting
  (let [output (#'args/overwrite
                {:string "string"
                 :coll   '(1 2 3)
                 :bool   true}
                {:bool   false
                 :coll   '(4)
                 :string "new"})]
    (testing "basic merging"
      (is (= '(1 2 3 4)
             (:coll output)))
      (is (= "new"
             (:string output))))))

(deftest fixing-options
  (let [opts (first (args/parse-args ["-p" "src1"] {:src-ns-path ["src2"]}))]
    (testing "collections are concatenated"
      (is (= '("src1" "src2")
             (:src-ns-path opts))))))

(deftest parse-custom-report
  (testing "Reading the custom-report from the args"
    (let [crep (-> ["--custom-report" "cloverage.custom-reporter/reporter-fn"]
                   (args/parse-args {:src-ns-path ["src"]})
                   (first)
                   :custom-report)]
      (is (symbol? crep)
          "parse the string symbol correctly")
      (is (= 'cloverage.custom-reporter/reporter-fn crep)
          "equivalent to the expected symbol")
      (is (= 42 (cloverage.coverage/launch-custom-report crep {:args [42]}))
          "launches the custom reporter")))
  (testing "Reading the custom-report from the project-opts"
    (let [crep (-> []
                   (args/parse-args {:src-ns-path ["src"]
                                     :custom-report 'cloverage.custom-reporter/reporter-fn})
                   first
                   :custom-report)]
      (is (= 'cloverage.custom-reporter/reporter-fn crep)
          "equivalent to the expected symbol")
      (is (= 42 (cloverage.coverage/launch-custom-report crep {:args [42]}))
          "launches the custom reporter"))))

(deftest validate!
  (are [input expected] (= expected
                           (try
                             (args/validate! input)
                             (catch ExceptionInfo e
                               (is (= "Invalid project settings"
                                      (ex-message e)))
                               (->> e
                                    ex-data
                                    :invalid-pairs
                                    (sort-by :validation-fn)))))
    {}                                nil
    {:custom-report 'a}               nil
    {:custom-report "not a symbol"}   [{:validation-fn 'clojure.core/symbol?
                                        :v             "not a symbol"
                                        :k             :custom-report}]
    {:ns-exclude-regex "not symbols"} [{:validation-fn 'cloverage.args/regexes-or-strings?,
                                        :v             "not symbols",
                                        :k             :ns-exclude-regex}]
    {:custom-report    "not a symbol"
     :ns-exclude-regex "not symbols"} [{:validation-fn 'clojure.core/symbol?,
                                        :v             "not a symbol",
                                        :k             :custom-report}
                                       {:validation-fn 'cloverage.args/regexes-or-strings?,
                                        :v             "not symbols",
                                        :k             :ns-exclude-regex}]))

(deftest parse-args
  (testing "Uses validation"
    (is (= {:invalid-pairs
            [{:k :runner, :v 'midje, :validation-fn `keyword?}]}
           (try
             (args/parse-args [] {:runner 'midje})
             (catch ExceptionInfo e
               (ex-data e)))))))
