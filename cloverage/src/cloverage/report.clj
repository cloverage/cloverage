(ns cloverage.report
  (:import [java.io File])
  (:use [clojure.java.io :only [writer reader copy]]
        [cloverage.source :only [resource-reader]])
  (:require clojure.pprint))

;; borrowed from duck-streams
(defmacro with-out-writer
  "Opens a writer on f, binds it to *out*, and evalutes body.
  Anything printed within body will be written to f."
  [f & body]
  `(with-open [stream# (writer ~f)]
     (binding [*out* stream#]
       ~@body)))

(defn- group-by-line [forms]
  (into (sorted-map) (group-by :line forms)))

(defn- group-by-file [forms]
  (into (sorted-map) (group-by :file forms)))

(defn- postprocess-file [lib file forms]
  (with-open [in (reader (resource-reader file))]
    (let [forms-by-line (group-by-line forms)
          make-rec (fn [line text]
                     (map (partial merge {:text text :line line
                                          :lib  lib  :file file})
                          (forms-by-line line [{:line line}])))
          line-nums (next (iterate inc 0))
          lines (into [] (line-seq in))]
      (mapcat make-rec line-nums lines))))

(defn gather-stats [forms]
  (let [forms-by-file (group-by :file forms)]
    (mapcat (fn [[file forms]] (postprocess-file (:lib (first forms)) file forms))
            forms-by-file)))

(defn line-stats [forms]
  (for [[line line-forms] (group-by-line forms)]
    (let [total (count (filter :tracked line-forms))
          hit   (count (filter :covered line-forms))]
      {:line     line
       :text     (:text (first line-forms))
       :total    total
       :hit      hit
       :blank?   (empty? (:text (first line-forms)))
       :covered? (and (> total 0) (= total hit))
       :partial? (and (> hit 0) (< hit total))
       :instrumented? (> total 0)})))

(defn file-stats [forms]
  (for [[file file-forms] (group-by :file forms)
        :let [lines (line-stats file-forms)]]
    {:file file
     :lib  (:lib  (first file-forms))

     :forms         (count (filter :tracked file-forms))
     :covered-forms (count (filter :covered file-forms))

     :lines         (count lines)
     :blank-lines   (count (filter :blank? lines))
     :instrd-lines  (count (filter :instrumented? lines))
     :covered-lines (count (filter :covered? lines))
     :partial-lines (count (filter :partial? lines))
     }))

(defn stats-report [file cov]
  (.mkdirs (.getParentFile file))
  (with-open [outf (writer file)]
    (binding [*out* outf]
      (printf "Lines Non-Blank Instrumented Covered Partial%n")
      (doseq [file-info (file-stats cov)]
        (printf "%5d %9d %7d %10d %10d %s%n"
                (:lines file-info)
                (- (:lines file-info) (:blank-lines file-info))
                (:instrd-lines  file-info)
                (:covered-lines file-info)
                (:partial-lines file-info)
                (:file          file-info))))))

(defn text-report [out-dir forms]
  (stats-report (File. out-dir "coverage.txt") forms)
  (doseq [[file file-forms] (group-by :file forms)
          :when file]
    (let [file (File. out-dir file)]
      (.mkdirs (.getParentFile file))
      (with-out-writer file
        (doseq [line (line-stats file-forms)]
          (let [prefix (cond (:blank?   line) " "
                             (:covered? line) "✔"
                             (:partial? line) "~"
                             (:instrumented? line) "✘"
                             :else           "?")]
            (println prefix (:text line))))))))

(defn- html-spaces [s]
  (.replace s " " "&nbsp;"))

(defn html-report [out-dir forms]
  (copy (resource-reader "coverage.css") (File. out-dir "coverage.css"))
  (stats-report (File. out-dir "coverage.txt") forms)
  (doseq [[rel-file file-forms] (group-by :file forms)]
    (let [file     (File. out-dir (str rel-file ".html"))
          rootpath (.relativize (.. file getParentFile toPath)
                                (.toPath (File. out-dir)))
          ]
      (.mkdirs (.getParentFile file))
      (with-out-writer file
        (println "<html>")
        (println " <head>")
        (printf "  <link rel=\"stylesheet\" href=\"%s/coverage.css\"/>" rootpath)
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
                (html-spaces (:text line " ")))))
        (println " </body>")
        (println "</html>")))))

(defn- td-bar [total & parts]
  (str "<td class=\"with-bar\">"
       (apply str
         (map (fn [[key cnt]]
                (format "<div class=\"%s\"
                              style=\"width:%s%%;
                                      float:left;\"> %d </div>"
                  (name key) (/ (* 100.0 cnt) total) cnt))
              parts))
       "</td>"))

(defn- td-num [content]
  (format "<td class=\"with-number\">%s</td>" content))

(defn html-summary [out-dir forms]
  (let [index (File. out-dir "index.html")
        rootpath (.relativize (.. index getParentFile toPath)
                              (.toPath (File. out-dir)))
        ]
    (.mkdirs (.getParentFile index))
    (with-out-writer index
      (println "<html>")
      (println " <head>")
      (printf  "  <link rel=\"stylesheet\" href=\"./%s/coverage.css\"/>" rootpath)
      (println "  <title>Coverage Summary</title>")
      (println " </head>")
      (println " <body>")
      (println "  <table>")
      (println "   <thead><tr>")
      (println "    <td> Namespace </td>")
      (println "    <td class=\"with-bar\"> Forms </td>")
      (println "    <td class=\"with-bar\"> Lines </td>")
      (println (apply str (map td-num ["Total" "Blank" "Instrumented"])))
      (println "   </tr></thead>")
      (doseq [file-stat (file-stats forms)]
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
              missed    (- instrd partial covered)
              ]
          (println "<tr>")
          (printf  " <td><a href=\"%s.html\">%s</a></td>" filepath libname)
          (println (td-bar forms [:covered cov-forms]
                                 [:not-covered mis-forms]))
          (println (td-bar instrd [:covered covered]
                                  [:partial partial]
                                  [:not-covered missed]))
          (println
            (apply str (map td-num [lines blank instrd])))
          (println "</tr>")
          ))
      (println "  </ul>")
      (println " </body>")
      (println "</html>")))
  )

(defn raw-report [out-dir stats covered]
  (with-out-writer (File. (File. out-dir) "raw-data.clj")
    (clojure.pprint/pprint (zipmap (range) covered)))
  (with-out-writer (File. (File. out-dir) "raw-stats.clj")
                   (clojure.pprint/pprint stats)))
