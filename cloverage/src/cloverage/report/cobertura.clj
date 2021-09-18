(ns cloverage.report.cobertura
  (:require
   [clojure.java.io :as io]
   [clojure.data.xml :as xml]
   [cloverage.report :refer [file-stats]]))

(defn- cov [t left right]
  (let [percent (if (pos? right) (float (* 100 (/ left right))) 0.0)]
    [:coverage {:type t :value (format "%.0f%% (%d/%d)" percent left right)}]))

(defn- do-counters [stats]
  {:lib            ((first stats) :lib)
   :form-count     (reduce + (map :forms stats))
   :cov-form-count (reduce + (map :covered-forms stats))
   :line-count     (reduce + (map :instrd-lines stats))
   :cov-line-count (reduce + (map :covered-lines stats))})

(defn- counters->cov [tag name cntrs]
  [tag {:name name}
   (cov "class, %" 0 1) (cov "method, %" 0 1)
   (cov "block, %" (cntrs :cov-form-count) (cntrs :form-count))
   (cov "line, %"  (cntrs :cov-line-count) (cntrs :line-count))])

(defn report
  "Create '${out-dir}/coverage.xml' in cobertura format"
  [^String out-dir forms]
  (let [output-file (io/file out-dir "cobertura.xml")
        stats       (doall (file-stats forms))
        file-count  (count (distinct (map :file stats)))
        lib-count   (count (distinct (map :lib stats)))
        total       (do-counters stats)
        by-pkg      (map do-counters (vals (group-by :lib stats)))]

    (println "Writing Cobertura report to:" (.getAbsolutePath output-file))
    (with-open [wr (io/writer output-file)]
      (-> [:coverage {:branch-rate      10
                      :branches-covered 0
                      :branches-valid   0
                      :complexity       0
                      :line-rate        0
                      :lines-covered    0
                      :lines-valid      3
                      :timestamp        (System/currentTimeMillis)
                      :version          "2.0.3"}
           [:sources]
           [:packages
            [:package {:line-rate   0.2
                       :branch-rate 0.3
                       :name        "name"
                       :complexity  0}]]]
          xml/sexp-as-element
          (xml/emit wr)))))
