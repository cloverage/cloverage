(ns cloverage.report.html
  (:require
   [clojure.string :as cs]
   [clojure.java.io :as io]
   [cloverage.source :refer [resource-reader]]
   [cloverage.report :refer [line-stats total-stats file-stats with-out-writer]]
   [hiccup.core :refer [html]])
  (:import
   [java.io File]))

(def html-escapes
  {\space "&nbsp;"
   \& "&amp;"
   \< "&lt;"
   \> "&gt;"
   \" "&quot;"
   \' "&#x27;"
   \/ "&#x2F;"})

;; Java 7 has a much nicer API, but this supports Java 6.
(defn relative-path [^File target-dir ^File base-dir]
  ^{:doc "Return the path to target-dir relative to base-dir.
          Both arguments are java.io.File"}
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

(defn- td-bar
  [& args]
  (let [total (first args)
        parts (rest args)]
    [:td.with-bar
     (for [[key cnt] parts]
       (when (pos? cnt)
         [:div {:class (name key), :style (format "width: %s%%; float: left" (/ (* 100.0 cnt) total))}
          (format " %d " cnt)]))]))

(defn- td-num [content]
  [:td.with-number content])

(defn- summary-template
  [forms totalled-stats]
  [:html
   [:head {:http-equiv "Content-Type", :content "text/html; charset=utf-8"}
    [:link {:rel "stylesheet", :href "./coverage.css"}]
    [:title "Coverage Summary"]
    [:body
     [:table
      [:thead
       [:tr
        [:td.ns-name " Namespace "]
        [:td.with-bar " Forms "]
        (td-num "Forms&nbsp;%")
        [:td.with-bar " Lines "]
        (td-num "Lines&nbsp;%")
        (for [title ["Total" "Blank" "Instrumented"]]
          (td-num title))]]
      [:tbody
       (for [file-stat (sort-by :lib (file-stats forms))]
         (let [filepath  (:file file-stat)
               libname   (:lib  file-stat)

               forms     (:forms file-stat)
               cov-forms (:covered-forms file-stat)
               mis-forms (- forms cov-forms)

               lines     (:lines file-stat)
               instrd    (:instrd-lines  file-stat)
               covered   (:covered-lines file-stat)
               partial   (:partial-lines file-stat)
               blank     (:blank-lines   file-stat)
               missed    (- instrd partial covered)]
           [:tr
            [:td [:a {:href (format "%s.html" filepath)} libname]]
            (td-bar forms [:covered cov-forms] [:not-covered mis-forms])
            (td-num (format "%.2f&nbsp;%%" (/ (* 100.0 cov-forms) forms)))
            (td-bar instrd [:covered covered] [:partial partial] [:not-covered missed])
            (td-num (format "%.2f&nbsp;%%" (/ (* 100.0 (+ covered partial)) instrd)))
            (for [text [lines blank instrd]]
              (td-num text))]))
       [:tr
        [:td "Totals:"]
        (td-bar)
        (td-num (format "%.2f %%" (:percent-forms-covered totalled-stats)))
        (td-bar)
        (td-num (format "%.2f %%" (:percent-lines-covered totalled-stats)))]]]]]])

(defn summary [^String out-dir forms]
  (let [output-file (io/file out-dir "index.html")
        totalled-stats (total-stats forms)]
    (println "Writing HTML report to:" (.getAbsolutePath output-file))
    (with-out-writer output-file
      (println (html (summary-template forms totalled-stats))))))

(defn- report-template
  [rootpath rel-file file-forms]
  [:html
   [:head {:http-equiv "Content-Type", :content "text/html; charset=utf-8"}
    [:link {:rel "stylesheet", :href (format "%scoverage.css" rootpath)}]
    [:title rel-file]]
   [:body
    (for [line (line-stats file-forms)]
      (let [cls (cond (:blank?        line) "blank"
                      (:covered?      line) "covered"
                      (:partial?      line) "partial"
                      (:instrumented? line) "not-covered"
                      :else            "not-tracked")]
        [:span {:class cls, :title (format "%d out of %d forms covered" (:hit line) (:total line))}
         (format "%03d&nbsp;&nbsp;%s" (:line line) (cs/escape (:text line " ") html-escapes))
         [:br]]))]])

(defn report [^String out-dir forms]
  (io/copy (resource-reader "coverage.css") (io/file out-dir "coverage.css"))
  (summary out-dir forms)
  (doseq [[rel-file file-forms] (group-by :file forms)]
    (let [file     (io/file out-dir (str rel-file ".html"))
          rootpath (relative-path (io/file out-dir) (.getParentFile file))]
      (io/make-parents file)
      (with-out-writer file
        (println (html (report-template rootpath rel-file file-forms)))))))
