(ns cloverage.report-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [cloverage.report.cobertura :as cob]
   [cloverage.report.emma-xml :as emma]
   [cloverage.report.lcov :as lcov]
   [cloverage.source :as source]
   [cloverage.report :as sut]
   [cloverage.report.html :refer [relative-path]]))

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
(def
  test-gathered-forms (first (parse-resource "cloverage/sample/raw-stats.clj")))

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
  (is (= {:percent-lines-covered 0.0, :percent-forms-covered 0.0} (sut/total-stats {}))))

(deftest gather-starts-works-on-empty-forms
  (is (= [] (sut/gather-stats []))))

(deftest gather-stats-converts-file-forms
  (with-redefs [sut/postprocess-file (fn [lib file _forms] {:lib lib :file file})]
    (is (= '([:lib "lib"] [:file "file"]) (sut/gather-stats [{:lib "lib" :file "file" :line 1}])))))

(deftest gather-starts-converts-raw-forms
  (with-redefs [source/resource-reader (fn [filename] (io/reader (get-resource-as-stream filename)))]
    (is (= test-gathered-forms (sut/gather-stats test-raw-forms)))))

(deftest lcov-report-writes-empty-report-with-no-forms
  (let [report (with-out-str (lcov/write-lcov-report []))]
    (is (= "" report))))

(deftest lcov-report-writes-report-with-forms
  (let [report (with-out-str (lcov/write-lcov-report test-gathered-forms))]
    (is (= "TN:\nSF:dev-resources/cloverage/sample/dummy_sample.clj\nDA:1,1\nDA:5,1\nDA:7,0\nLF:3\nLH:2\nend_of_record\n" report))))

(spit "lcov.dat" "TN:\nSF:dev-resources/cloverage/sample/dummy_sample.clj\nDA:1,1\nDA:5,1\nDA:7,0\nLF:3\nLH:2\nend_of_record\n")

(deftest emma-report-with-forms
  (emma/report "." test-gathered-forms)
  (is (= "<?xml version=\"1.0\" encoding=\"UTF-8\"?><report><stats><packages value=\"1\"></packages><methods value=\"5\"></methods><srcfiles value=\"1\"></srcfiles><srclines value=\"3\"></srclines></stats><data><all name=\"total\"><coverage type=\"class, %\" value=\"0% (0/1)\"></coverage><coverage type=\"method, %\" value=\"0% (0/1)\"></coverage><coverage type=\"block, %\" value=\"40% (2/5)\"></coverage><coverage type=\"line, %\" value=\"67% (2/3)\"></coverage><package name=\"cloverage.sample.dummy-sample\"><coverage type=\"class, %\" value=\"0% (0/1)\"></coverage><coverage type=\"method, %\" value=\"0% (0/1)\"></coverage><coverage type=\"block, %\" value=\"40% (2/5)\"></coverage><coverage type=\"line, %\" value=\"67% (2/3)\"></coverage></package></all></data></report>"
         (slurp "coverage.xml"))))

(deftest cobertura-report
  (cob/report "." test-gathered-forms)
  (is (= "<?xml version=\"1.0\" ?>\n<!DOCTYPE coverage\n  SYSTEM 'http://cobertura.sourceforge.net/xml/coverage-04.dtd'>\n<coverage branch-rate=\"0.0\" branches-covered=\"0\" branches-valid=\"0\" complexity=\"0\" line-rate=\"0.6666666666666666\" lines-covered=\"2\" lines-valid=\"3\" timestamp=\"1631999222\" version=\"2.0.3\">\n\t<sources>\n\t\t<source>.</source>\n\t</sources>\n\t<packages>\n\t\t<package line-rate=\"0.6666666666666666\" branch-rate=\"0.0\" name=\"dev-resources.cloverage.sample\" complexity=\"0\">\n\t\t\t<classes>\n\t\t\t\t<class branch-rate=\"0.0\" complexity=\"0\" filename=\"dev-resources/cloverage/sample/dummy_sample.clj\" line-rate=\"0.6666666666666666\" name=\"dev-resources.cloverage.sample.dummy_sample.clj\">\n\t\t\t\t\t<methods/>\n\t\t\t\t\t<lines>\n\t\t\t\t\t\t<line branch=\"false\" hits=\"1\" number=\"1\"/>\n\t\t\t\t\t\t<line branch=\"false\" hits=\"1\" number=\"5\"/>\n\t\t\t\t\t\t<line branch=\"false\" hits=\"0\" number=\"7\"/>\n\t\t\t\t\t</lines>\n\t\t\t\t</class>\n\t\t\t</classes>\n\t\t</package>\n\t</packages>\n</coverage>\n"
         (slurp "cobertura.xml"))))
