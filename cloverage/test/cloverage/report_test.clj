(ns cloverage.report-test
  (:require
   [clojure.test :refer :all]
   [cloverage.report :refer :all]
   [cloverage.report.html :refer [relative-path]]
   [clojure.tools.reader :as r]
   [clojure.java.io :as io]))

(defn- parse-readable
  "parse in all forms from reader"
  [reader]
  (with-open [pb-reader (java.io.PushbackReader. reader)]
    (binding [*read-eval* false]
      (let [exp-reader (repeatedly #(read pb-reader false nil))]
        (doall (take-while identity exp-reader))))))

(defn- get-resource-as-stream
  "open resource from classpath as stream"
  [resource]
  (.getResourceAsStream (clojure.lang.RT/baseLoader) resource))

(defn- parse-resource
  "opens the resource-name and returns the parsed forms"
  [resource-name]
  (parse-readable (io/reader (get-resource-as-stream resource-name))))

(def test-raw-forms (vals (first (parse-resource "cloverage/sample/raw-data.clj"))))
(def test-gathered-forms (first (parse-resource "cloverage/sample/raw-stats.clj")))

(deftest test-relative-path
  (is (= "child/" (relative-path (io/file "parent/child/") (io/file "parent/"))))
  (is (= "parent/child/"
         (relative-path (io/file "shared" "parent/child/") (io/file "shared/"))))
  (is (= "parent/long dir name/"
         (relative-path (io/file "shared/parent/long dir name") (io/file "shared"))))
  (is (= "../dir2/child/"
         (relative-path (io/file "dir2/child/") (io/file "dir"))))
  (is (= "dir/" (relative-path (io/file "/tmp/dir/") (io/file "/tmp"))))
  (is (= "" (relative-path (io/file "/") (io/file "/"))))
  (is (= "" (relative-path (io/file "dir/file/") (io/file "dir/file/")))))

(deftest total-stats-zero
  (is (= {:percent-lines-covered 0.0, :percent-forms-covered 0.0} (total-stats {}))))

(deftest gather-starts-works-on-empty-forms
  (is (= [] (gather-stats []))))

(deftest gather-stats-converts-file-forms
  (with-redefs [cloverage.report/postprocess-file (fn [lib file forms] {:lib lib :file file})]
    (is (= '([:lib "lib"] [:file "file"]) (gather-stats [{:lib "lib" :file "file" :line 1}])))))

(deftest gather-starts-converts-raw-forms
  (with-redefs [cloverage.source/resource-reader (fn [filename] (io/reader (get-resource-as-stream filename)))]
    (is (= test-gathered-forms (gather-stats test-raw-forms)))))

(deftest lcov-report-writes-empty-report-with-no-forms
  (let [report (with-out-str (#'cloverage.report.lcov/write-lcov-report []))]
    (is (= "" report))))

(deftest lcov-report-writes-report-with-forms
  (let [test-forms test-gathered-forms
        report (with-out-str (#'cloverage.report.lcov/write-lcov-report test-forms))]
    (is (= "TN:\nSF:cloverage/sample/dummy_sample.clj\nDA:1,1\nDA:5,1\nDA:7,0\nLF:3\nLH:2\nend_of_record\n" report))))
