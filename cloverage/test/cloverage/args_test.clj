(ns cloverage.args-test
  (:require [clojure.test :refer :all]
            [cloverage.args :as args]))

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
