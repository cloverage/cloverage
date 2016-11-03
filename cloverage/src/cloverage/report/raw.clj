(ns cloverage.report.raw
  (:require
   [clojure.java.io :as io]
   [cloverage.report :refer [with-out-writer]]))

(defn report [^String out-dir stats covered]
  (let [raw-data-file (io/file out-dir "raw-data.clj")
        raw-stats-file (io/file out-dir "raw-stats.clj")]

    (println "Writing raw data to:" (.getAbsolutePath raw-data-file))
    (with-out-writer raw-data-file
      (clojure.pprint/pprint (zipmap (range) covered)))

    (println "Writing raw stats to:" (.getAbsolutePath raw-stats-file))
    (with-out-writer raw-stats-file
      (clojure.pprint/pprint stats))))

