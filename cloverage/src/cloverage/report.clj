(ns cloverage.report
  (:import
   [java.io File])
  (:require
   [clojure.java.io :as io]
   [cloverage.source :refer [resource-reader]]))

;; borrowed from duck-streams
(defmacro with-out-writer
  "Opens a writer on f, binds it to *out*, and evalutes body.
  Anything printed within body will be written to f."
  [^File f & body]
  `(with-open [stream# (io/writer ~f)]
     (binding [*out* stream#]
       ~@body)))

(defn- group-by-line [forms]
  (into (sorted-map) (group-by :line forms)))

(defn- group-by-file [forms]
  (into (sorted-map) (group-by :file forms)))

(defn- postprocess-file [lib file forms]
  (with-open [in (io/reader (resource-reader file))]
    (let [forms-by-line (group-by-line forms)
          make-rec (fn [line text]
                     (map (partial merge {:text text :line line
                                          :lib  lib  :file file})
                          (forms-by-line line [{:line line}])))
          line-nums (iterate inc 1)
          lines (vec (line-seq in))]
      (mapcat make-rec line-nums lines))))

(defn gather-stats [forms]
  (let [forms-by-file (group-by :file forms)]
    (mapcat (fn [[file forms]] (postprocess-file (:lib (first forms)) file forms))
            forms-by-file)))

(defn line-stats [forms]
  (for [[line line-forms] (group-by-line forms)]
    (let [total (count (filter :tracked line-forms))
          hit   (count (filter :covered line-forms))
          times-hit (if (zero? hit)
                      hit
                      (apply max (filter number?
                                         (map :hits line-forms))))]
      {:line     line
       :text     (:text (first line-forms))
       :total    total
       :hit      hit
       :times-hit times-hit
       :blank?   (empty? (:text (first line-forms)))
       :covered? (and (> total 0) (= total hit))
       :partial? (< 0 hit total)
       :instrumented? (> total 0)})))

(defn file-stats [forms]
  (for [[file file-forms] (group-by :file forms)
        :let [lines (line-stats file-forms)]]
    {:file          file
     :lib           (:lib  (first file-forms))

     :forms         (count (filter :tracked file-forms))
     :covered-forms (count (filter :covered file-forms))

     :lines         (count lines)
     :blank-lines   (count (filter :blank? lines))
     :instrd-lines  (count (filter :instrumented? lines))
     :covered-lines (count (filter :covered? lines))
     :partial-lines (count (filter :partial? lines))}))

(defn total-stats [forms]
  (let [all-file-stats (file-stats forms)
        total      #(reduce + (map % all-file-stats))
        covered    (total :covered-lines)
        partial    (total :partial-lines)
        lines      (total :instrd-lines)
        cov-forms  (total :covered-forms)
        forms      (total :forms)]
    {:percent-lines-covered (if (= lines 0) 0. (* (/ (+ covered partial) lines) 100.0))
     :percent-forms-covered (if (= forms 0) 0. (* (/ cov-forms forms) 100.0))}))

