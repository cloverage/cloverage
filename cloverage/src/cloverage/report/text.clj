(ns cloverage.report.text
  (:import
   [java.io File])
  (:require
   [clojure.java.io :as io]
   [cloverage.report :refer [line-stats file-stats with-out-writer]]))

(defn summary [^File file forms]
  (println "Writing text report to:" (.getAbsolutePath file))
  (with-out-writer file
    (printf "Lines Non-Blank Instrumented Covered Partial%n")
    (doseq [file-info (file-stats forms)]
      (printf "%5d %9d %12d %7d %7d %s%n"
              (:lines file-info)
              (- (:lines file-info) (:blank-lines file-info))
              (:instrd-lines  file-info)
              (:covered-lines file-info)
              (:partial-lines file-info)
              (:file          file-info)))))

(defn report [^String out-dir forms]
  (summary (io/file out-dir "coverage.txt") forms)
  (doseq [[file file-forms] (group-by :file forms)
          :when file]
    (let [output-file (io/file out-dir file)]
      (.mkdirs (.getParentFile output-file))
      (with-out-writer output-file
        (doseq [line (line-stats file-forms)]
          (let [prefix (cond (:blank?   line) " "
                             (:covered? line) "✔"
                             (:partial? line) "~"
                             (:instrumented? line) "✘"
                             :else           "?")]
            (println prefix (:text line))))))))
