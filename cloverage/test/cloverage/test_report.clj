(ns cloverage.test-report
  (:import java.io.File)
  (:use clojure.test
        cloverage.report))

(deftest test-relative-path
  (is (= "child/" (relative-path (File. "parent/child/") (File. "parent/"))))
  (is (= "parent/child/"
         (relative-path (File. "shared" "parent/child/") (File. "shared/"))))
  (is (= "parent/long dir name/"
         (relative-path (File. "shared/parent/long dir name") (File. "shared"))))
  (is (= "../dir2/child/"
         (relative-path (File. "dir2/child/") (File. "dir"))))
  (is (= "dir/" (relative-path (File. "/tmp/dir/") (File. "/tmp"))))
  (is (= "" (relative-path (File. "/") (File. "/"))))
  (is (= "" (relative-path (File. "dir/file/") (File. "dir/file/")))))
