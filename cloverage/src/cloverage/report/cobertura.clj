(ns cloverage.report.cobertura
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [cloverage.report :refer [line-stats with-out-writer]]
            [cloverage.source :refer [source-file-path]]))

(defn- line-rate [covered total]
  (if (pos? total) (/ (double covered) total) 0.0))

(defn- file-coverage [[file forms]]
  (let [lib (str (:lib (first forms)))
        last-dot (.lastIndexOf ^String lib ".")
        lines (vec (filter :instrumented? (line-stats forms)))]
    {:name lib
     :filename (source-file-path file)
     :package (if (neg? last-dot) "" (subs lib 0 last-dot))
     :lines lines
     :valid (count lines)
     :covered (count (filter #(pos? (:times-hit %)) lines))}))

(defn- totals [files]
  (let [covered (reduce + (map :covered files))
        valid (reduce + (map :valid files))]
    {:covered covered :valid valid :rate (line-rate covered valid)}))

(defn- class-element [{:keys [name filename lines valid covered]}]
  [:class {:name name :filename filename
           :line-rate (line-rate covered valid) :branch-rate 0.0 :complexity 0}
   [:methods]
   (into [:lines]
         (map (fn [line]
                [:line {:number (:line line) :hits (:times-hit line) :branch "false"}])
              lines))])

(defn- package-element [[package files]]
  [:package {:name package :line-rate (:rate (totals files)) :branch-rate 0.0 :complexity 0}
   (into [:classes] (map class-element files))])

(defn write-cobertura-report
  "Write a Cobertura XML report to *out* from gather-stats records, which include :lib.
  Branch and method coverage are not tracked by Cloverage."
  [forms]
  (let [files (mapv file-coverage (sort-by first (filter first (group-by :file forms))))
        {:keys [covered valid rate]} (totals files)]
    (xml/emit
     (xml/sexp-as-element
      [:coverage {:line-rate rate :lines-covered covered :lines-valid valid
                  :branch-rate 0.0 :branches-covered 0 :branches-valid 0
                  :complexity 0 :version "cloverage"
                  :timestamp (quot (System/currentTimeMillis) 1000)}
       [:sources [:source "."]]
       (into [:packages]
             (map package-element (sort-by first (group-by :package files))))])
     *out*)))

(defn report
  "Write a Cobertura XML report to '${out-dir}/cobertura.xml'."
  [^String out-dir forms]
  (let [output-file (io/file out-dir "cobertura.xml")]
    (println "Writing Cobertura report to:" (.getAbsolutePath output-file))
    (with-out-writer output-file
      (write-cobertura-report forms))))
