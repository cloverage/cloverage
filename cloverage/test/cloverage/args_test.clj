(ns cloverage.args-test
  (:require [clojure.test :refer :all]
            [cloverage.args :as args]
            [cloverage.coverage]))

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
             (:string output))))

    (testing "removing the invalid keys"
      (let [output (#'args/overwrite
                    {}
                    {:test-ns-regex [#"" ""]
                     :src-ns-path   [1]})]
        (is (nil? (find output :src-ns-path)))))))

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
