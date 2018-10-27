(ns cloverage.report.html
  (:require
   [clojure.string :as cs]
   [clojure.java.io :as io]
   [cloverage.source :refer [resource-reader]]
   [cloverage.report :refer [line-stats total-stats file-stats with-out-writer]]
   [hiccup.core :as h]
   [hiccup.page :as page])
  (:import
   [java.io File]))

(def JQUERY-SORTING "jquery.tablesorter.min.js")
(def JQUERY "jquery-3.3.1.min.js")
(def START "start.js")
(def STYLESHEET "coverage.css")
(defn css [& args]
  (->> args
       (into {})
       (map (fn [[k v]]
              (str (name k) \: v)))
       (cs/join \;)))

;; Java 7 has a much nicer API, but this supports Java 6.
(defn relative-path
  "Return the path to target-dir relative to base-dir.
Both arguments are java.io.File"
  [^File target-dir ^File base-dir]
  (loop [target-file (.getAbsoluteFile target-dir)
         base-file   (.getAbsoluteFile base-dir)
         postpend    ""
         prepend     ""]
    (let [target-path (.getAbsolutePath target-file)
          base-path   (.getAbsolutePath base-file)]
      (cond
        (= base-path target-path)
        (apply str prepend postpend)

        (> (count base-path)
           (count target-path))
        (recur target-file (.getParentFile base-file) postpend (str prepend "../"))

        :else
        (let [new-target (.getParentFile target-file)
              suffix     (subs target-path
                               (count (.getAbsolutePath new-target)))]
          (recur new-target base-file
                 (str (subs suffix 1) "/" postpend)
                 prepend))))))

(defn- td-bar [total & parts]
  [:td.with-bar
   (->> parts
        (filter (fn [[_ cnt]]
                  (pos? cnt)))
        (map (fn [[key cnt]]
               [:div {:class (name key)
                      :style (css {"width" (str (/ (* 100.0 cnt) total) "%")
                                   "float" "left"})}
                cnt])))])

(defn- td-num [content]
  [:td.with-number content])

(defn- thead []
  [:thead
   [:tr
    [:td.ns-name "Namespace"]
    [:td.with-bar "Forms"]
    (td-num "Forms %")
    [:td.with-bar "Lines"]
    (td-num "Lines %")
    (td-num "Total")
    (td-num "Blank")
    (td-num "Instrumented")]])

(defn- head [{:keys [title
                     main?
                     rootpath]}]
  (letfn [(path [f] (format "%s%s" rootpath f))]
    [:head
     (concat
      [[:meta {:http-equiv "Content-Type"
               :content    "text/html; charset=utf-8"}]
       [:title title]]
      (->> [STYLESHEET]
           (map path)
           (apply page/include-css))
      (when main?
        (->> [JQUERY JQUERY-SORTING]
             (map path)
             (apply page/include-js))))]))

(defn- summary-line [file-stat]
  (let [filepath  (:file file-stat)
        libname   (:lib file-stat)

        forms     (:forms file-stat)
        cov-forms (:covered-forms file-stat)
        mis-forms (- forms cov-forms)

        lines     (:lines file-stat)
        instrd    (:instrd-lines file-stat)
        covered   (:covered-lines file-stat)
        partial   (:partial-lines file-stat)
        blank     (:blank-lines file-stat)
        missed    (- instrd partial covered)]
    [:tr
     [:td [:a {:href (format "%s.html" filepath)} libname]]
     (td-bar forms [:covered cov-forms]
             [:not-covered mis-forms])
     (td-num (format "%.2f %%" (/ (* 100.0 cov-forms) forms)))
     (td-bar instrd [:covered covered]
             [:partial partial]
             [:not-covered missed])
     (td-num (format "%.2f %%" (/ (* 100.0 (+ covered partial))
                                  instrd)))
     (td-num lines)
     (td-num blank)
     (td-num instrd)]))

(defn- total-line
  [{:keys [:percent-forms-covered
           :percent-lines-covered]}]
  [:tr
   [:td "Totals:"]
   (td-bar nil)
   (td-num (format "%.2f %%" percent-forms-covered))
   (td-bar nil)
   (td-num (format "%.2f %%" percent-lines-covered))
   (td-num "")
   (td-num "")
   (td-num "")])

(defn summary [^String out-dir forms]
  (let [output-file    (io/file out-dir "index.html")
        totalled-stats (total-stats forms)]
    (println "Writing HTML report to:" (.getAbsolutePath output-file))
    (with-out-writer output-file
      (println
       (h/html
        [:html
         (head {:title    "Coverage Summary"
                :rootpath "./"
                :main?    true})
         [:body
          [:table#summary
           (thead)
           [:tbody
            (for [file-stat (sort-by :lib (file-stats forms))]
              (summary-line file-stat))]
           [:tfoot
            (total-line totalled-stats)]]
          (page/include-js (str "./" START))]])))))

(defn copy-resource [out-dir n]
  (io/copy (resource-reader n) (io/file out-dir n)))

(defn report [^String out-dir forms]
  (doseq [f [STYLESHEET
             START
             JQUERY
             JQUERY-SORTING]]
    (copy-resource out-dir f))

  (summary out-dir forms)
  (doseq [[rel-file file-forms] (group-by :file forms)]
    (let [file     (io/file out-dir (str rel-file ".html"))
          rootpath (relative-path (io/file out-dir) (.getParentFile file))]
      (io/make-parents file)
      (with-out-writer file
        (println
         (h/html
          [:html
           (head {:title    rel-file
                  :rootpath rootpath})
           [:body
            (interpose
             [:br]
             (for [line (line-stats file-forms)]
               (let [cls (cond (:blank? line) "blank"
                               (:covered? line) "covered"
                               (:partial? line) "partial"
                               (:instrumented? line) "not-covered"
                               :else "not-tracked")]
                 [:span {:class cls
                         :title (format "%d out of %d forms covered" (:hit line) (:total line))}
                  (str (format "%03d" (:line line))
                       (:text line " "))])))]]))))))
