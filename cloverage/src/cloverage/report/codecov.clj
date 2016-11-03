(ns cloverage.report.codecov
  (:require
   [clojure.java.io :as io]
   [clojure.data.json :as json]
   [cloverage.report :refer [line-stats with-out-writer]]))

(defn- file-coverage [[file file-forms]]
  ;; https://codecov.io/api#post-json-report
  ;; > 0: covered (number of times hit)
  ;; true: partially covered
  ;; 0: not covered
  ;; null: skipped/ignored/empty
  ;; the first item in the list must be a null
  (vector file
          (cons nil
                (mapv (fn [line]
                        (cond (:blank?   line) nil
                              (:covered? line) (:times-hit line)
                              (:partial? line) true
                              (:instrumented? line) 0
                              :else nil)) (line-stats file-forms)))))

(defn report [^String out-dir forms]
  (let [output-file (io/file out-dir "codecov.json")
        covdata (->>
                 forms
                 (group-by :file)
                 (filter first)
                 (map file-coverage)
                 (into {}))]

    (println "Writing codecov.io report to:" (.getAbsolutePath output-file))
    (with-out-writer output-file
      (json/pprint {:coverage covdata} :escape-slash false))))

