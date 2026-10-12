(ns leiningen.test-cloverage
  (:require [clojure.test :refer [deftest is testing]]
            [leiningen.cloverage :as sut]
            [leiningen.core.eval :as lein-eval]
            [leiningen.core.main :as main]
            [leiningen.core.project :as project]))

(defn- invocation [p & args]
  (let [result (atom nil)]
    (with-redefs [lein-eval/eval-in-project
                  (fn [p form init]
                    (let [call (first (filter #(and (seq? %)
                                                    (= 'cloverage.coverage/run-project (first %)))
                                              (tree-seq coll? seq form)))]
                      (reset! result {:project p
                                      :options (eval (second call))
                                      :args (vec (drop 2 call))
                                      :init init})))]
      (apply sut/cloverage
             (project/project-with-profiles-meta p (merge {:test {} :leiningen/test {}}
                                                          (:profiles p)))
             args))
    @result))

(deftest preserves-explicit-library-version
  (let [p (project/make {:dependencies [['cloverage/cloverage "1.2.2"]]})
        result (invocation p)]
    (is (= [['cloverage/cloverage "1.2.2"]]
           (filter #(#{'cloverage 'cloverage/cloverage} (first %))
                   (:dependencies (:project result)))))))

(deftest injects-library-when-absent
  (with-redefs [sut/get-lib-version (constantly "test-version")]
    (let [result (invocation (project/make {}))]
      (is (some #{['cloverage "test-version"]}
                (:dependencies (:project result)))))))

(deftest forwards-project-options-and-command-line-arguments
  (let [p (project/make {:source-paths ["source"]
                         :test-paths ["tests"]
                         :cloverage {:output "project-report" :text? true}
                         :profiles {:test {:test-paths ["extra-tests"]}}
                         :test-selectors {:fast '(fn [m] (:fast m))}})
        {:keys [options args init]} (invocation p "--output" "cli-report" "--no-html")]
    (is (= ["source"] (:src-ns-path options)))
    (is (= #{"tests" "extra-tests"} (set (:test-ns-path options))))
    (is (= "project-report" (:output options)))
    (is (true? (:text? options)))
    (is (true? ((get-in options [:test-selectors :fast]) {:fast true})))
    (is (= ["--output" "cli-report" "--no-html"] args))
    (is (= '(require 'cloverage.coverage) init))))

(deftest propagates-evaluation-failures
  (doseq [[data expected] [[{:exit-code 7} 7] [{} 1]]]
    (testing (str "Exception data " data)
      (let [exit-code (atom nil)]
        (with-redefs [lein-eval/eval-in-project (fn [& _] (throw (ex-info "failed" data)))
                      main/exit (fn [code] (reset! exit-code code))]
          (sut/cloverage (project/make {})))
        (is (= expected @exit-code))))))
