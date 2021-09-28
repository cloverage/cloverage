(ns cloverage.report.cobertura
  (:require
   [clojure.java.io :as io]
   [clojure.data.xml :as xml]
   [clojure.string :as str]
   [cloverage.report :refer [file-stats]]))

(defn line-stats [file-s]
  [:line {:branch "false"
          :hits 1
          :number 3}])

(defn files [by-file]
  [:classes
   [:class {}
    [:methods]
    (into [:lines]
          (map line-stats by-file))]])

(defn packages [by-package]
  [:packages
   [:package {:line-rate   0.2
              :branch-rate 0.3
              :name        "name"
              :complexity  0}
    (into [:classes]
          (map files by-package))]])

(defn f->pkg [filename]
  (let [sp (str/split (str/replace filename "/" ".") #"\.")]
    (->> sp
         (take-nth (- (count sp) 2))
         (str/join "."))))

(defn report
  "Create '${out-dir}/cobertura.xml' in cobertura format"
  [^String out-dir forms]
  ;; now with the stats above run the whole thing
  (let [output-file (io/file out-dir "cobertura.xml")
        stats       (doall (file-stats forms))
        with-package (map #(assoc % :package (f->pkg (:file %))) stats)

        ;; file-count  (count (distinct (map :file stats)))
        ;; lib-count   (count (distinct (map :lib stats)))
        ;; total       (do-counters stats)
        ;; by-pkg      (map do-counters (vals (group-by :lib stats)))
        ]

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
           ;; what is this `sources` for???
           [:sources [:source "."]]
           (into
            [:packages]
            (packages (group-by :package with-package)))]
          xml/sexp-as-element
          (xml/emit wr)))))
