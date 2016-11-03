(ns cloverage.report.html
  (:require
   [clojure.string :as cs]
   [clojure.java.io :as io]
   [cloverage.source :refer [resource-reader]]
   [cloverage.report :refer [line-stats total-stats file-stats with-out-writer]])
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

(defn- td-bar [total & parts]
  (str "<td class=\"with-bar\">"
       (apply str
              (map (fn [[key cnt]]
                     (if (> cnt 0)
                       (format "<div class=\"%s\"
                                style=\"width:%s%%;
                                        float:left;\"> %d </div>"
                               (name key) (/ (* 100.0 cnt) total) cnt)
                       ""))
                   parts))
       "</td>"))

(defn- td-num [content]
  (format "<td class=\"with-number\">%s</td>" content))

(defn summary [^String out-dir forms]
  (let [output-file (io/file out-dir "index.html")
        totalled-stats (total-stats forms)]
    (println "Writing HTML report to:" (.getAbsolutePath output-file))
    (with-out-writer output-file
      (println "<html>")
      (println " <head>")
      (println "   <meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">")
      (println "  <link rel=\"stylesheet\" href=\"./coverage.css\"/>")
      (println "  <title>Coverage Summary</title>")
      (println " </head>")
      (println " <body>")
      (println "  <table>")
      (println "   <thead><tr>")
      (println "    <td class=\"ns-name\"> Namespace </td>")
      (println "    <td class=\"with-bar\"> Forms </td>")
      (println (td-num "Forms %"))
      (println "    <td class=\"with-bar\"> Lines </td>")
      (println (td-num "Lines %"))
      (println (apply str (map td-num ["Total" "Blank" "Instrumented"])))
      (println "   </tr></thead>")
      (doseq [file-stat (sort-by :lib (file-stats forms))]
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
          (println "<tr>")
          (printf  " <td><a href=\"%s.html\">%s</a></td>" filepath libname)
          (println (td-bar forms [:covered cov-forms]
                           [:not-covered mis-forms]))
          (println (td-num (format "%.2f %%" (/ (* 100.0 cov-forms) forms))))
          (println (td-bar instrd [:covered covered]
                           [:partial partial]
                           [:not-covered missed]))
          (println (td-num (format "%.2f %%" (/ (* 100.0 (+ covered partial))
                                                instrd))))
          (println
           (apply str (map td-num [lines blank instrd])))
          (println "</tr>")))
      (println "<tr><td>Totals:</td>")
      (println (td-bar nil))
      (println (td-num (format "%.2f %%" (:percent-forms-covered totalled-stats))))
      (println (td-bar nil))
      (println (td-num (format "%.2f %%" (:percent-lines-covered totalled-stats))))
      (println "   </tr>")
      (println "  </table>")
      (println " </body>")
      (println "</html>"))))

(defn report [^String out-dir forms]
  (io/copy (resource-reader "coverage.css") (io/file out-dir "coverage.css"))
  (summary out-dir forms)
  (doseq [[rel-file file-forms] (group-by :file forms)]
    (let [file     (io/file out-dir (str rel-file ".html"))
          rootpath (relative-path (io/file out-dir) (.getParentFile file))]
      (with-out-writer file
        (println "<html>")
        (println " <head>")
        (println "   <meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">")
        (printf "  <link rel=\"stylesheet\" href=\"%scoverage.css\"/>" rootpath)
        (println "  <title>" rel-file "</title>")
        (println " </head>")
        (println " <body>")
        (doseq [line (line-stats file-forms)]
          (let [cls (cond (:blank?        line) "blank"
                          (:covered?      line) "covered"
                          (:partial?      line) "partial"
                          (:instrumented? line) "not-covered"
                          :else            "not-tracked")]
            (printf
             "<span class=\"%s\" title=\"%d out of %d forms covered\">
                 %03d&nbsp;&nbsp;%s
                </span><br/>%n"
             cls (:hit line) (:total line)
             (:line line)
             (cs/escape (:text line " ") html-escapes))))
        (println " </body>")
        (println "</html>")))))

