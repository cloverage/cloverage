(ns cloverage.source-path-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [cloverage.source :as source]
            [cloverage.report.codecov :as codecov])
  (:import [java.net URI]))

(deftest source-file-path-handles-resource-locations
  (doseq [[label url expected]
          [["local source" (.toURL (.toURI (io/file "src/foo/core.clj"))) "src/foo/core.clj"]
           ["spaces in local paths" (.toURL (.toURI (io/file "src dir/foo/core.clj"))) "src dir/foo/core.clj"]
           ["JAR source" (.toURL (URI. "jar:file:/tmp/library.jar!/foo/core.clj")) "foo/core.clj"]
           ["outside the project" (.toURL (.toURI (io/file "../other-project/foo/core.clj"))) "../other-project/foo/core.clj"]
           ["missing source" nil "foo/core.clj"]]]
    (testing label
      (with-redefs [io/resource (fn [& _] url)]
        (is (= expected (source/source-file-path "foo/core.clj")))))))

(deftest codecov-report-uses-resolved-path-without-changing-coverage
  (let [forms [{:line 1 :text "covered" :tracked true :covered true :hits 3}
               {:line 2 :text "missed" :tracked true :covered false :hits 0}
               {:line 3 :text ""}]]
    (doseq [[url expected]
            [[(.toURL (.toURI (io/file "src/foo/core.clj"))) "src/foo/core.clj"]
             [(.toURL (URI. "jar:file:/tmp/library.jar!/foo/core.clj")) "foo/core.clj"]]]
      (with-redefs [io/resource (fn [& _] url)]
        (is (= [expected [nil 3 0 nil]]
               (#'codecov/file-coverage ["foo/core.clj" forms])))))))
