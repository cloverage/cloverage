(ns cloverage.report.lcov
  (:require
   [clojure.java.io :as io]
   [cloverage.report :refer [line-stats with-out-writer]]))

(defn write-lcov-report
  "Write out lcov report to *out*"
  [forms]
  (doseq [[rel-file file-forms] (group-by :file forms)]
    (let [lines (line-stats file-forms)
          instrumented (filter :instrumented? lines)]
      (println "TN:")
      (printf "SF:%s%n" rel-file)
      (doseq [line instrumented]
        (printf "DA:%d,%d%n" (:line line) (:hit line)))
      (printf "LF:%d%n" (count instrumented))
      (printf "LH:%d%n" (count (filter (fn [line] (> (:hit line) 0)) lines)))
      (println "end_of_record"))))

(defn report
  "Write LCOV report to '${out-dir}/lcov.info'."
  [^String out-dir forms]
  (let [output-file (io/file out-dir "lcov.info")]
    (println "Writing LCOV report to:" (.getAbsolutePath output-file))
    (with-out-writer output-file (write-lcov-report forms))))

