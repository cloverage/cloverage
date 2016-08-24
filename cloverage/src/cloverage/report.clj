(ns cloverage.report
  (:import [java.io File]
	   [java.security MessageDigest]
	   [java.math BigInteger])
  (:use [clojure.java.io :only [writer reader copy]]
        [cloverage.source :only [resource-reader]])
  (:require clojure.pprint
            [clojure.string :as cs]
            [clojure.data.xml :as xml]
            [cheshire.core :as json]))

(def html-escapes
  { \space "&nbsp;"
    \& "&amp;"
    \< "&lt;"
    \> "&gt;"
    \" "&quot;"
    \' "&#x27;"
    \/ "&#x2F;"})

(defn md5 [s]
  (let [algorithm (MessageDigest/getInstance "MD5")
	size (* 2 (.getDigestLength algorithm))
	raw (.digest algorithm (.getBytes s))
	sig (.toString (BigInteger. 1 raw) 16)
	padding (apply str (repeat (- size (count sig)) "0"))]
    (str padding sig)))

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

(defn- cov [t left right]
  (let [percent (if (> right 0) (float (* 100 (/ left right))) 0.0)]
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

(defn emma-xml-report
  "Create '${out-dir}/coverage.xml' in EMMA format (emma.sourceforge.net)."
  [out-dir forms]
  (let [output-file (File. out-dir "coverage.xml")
        stats      (doall (file-stats forms))
        file-count (count (distinct (map :file stats)))
        lib-count  (count (distinct (map :lib stats)))
        total      (do-counters stats)
        by-pkg     (map do-counters (vals (group-by :lib stats)))]
    (.mkdirs (.getParentFile output-file))
    (with-open [wr (writer output-file)]
      (-> [:report
           [:stats (map #(vector %1 {:value %2})
                        [:packages :methods :srcfiles :srclines]
                        [lib-count (total :form-count) file-count (total :line-count)])]
           [:data (apply conj (counters->cov :all "total" total)
                         (map #(counters->cov :package (% :lib) %) by-pkg))]]
          xml/sexp-as-element (xml/emit wr)))
    nil))

(defn- write-lcov-report
  "Write out lcov report to *out*"
  [forms]
  (doseq [[rel-file file-forms] (group-by :file forms)]
        (let [lines (line-stats file-forms)
              instrumented (filter :instrumented? lines)]
          (println "TN:")
          (printf "SF:%s%n" rel-file)
          (doseq [line instrumented]
            (printf "DA:%d,%d%n" (:line line) (:hit line)))
          (printf "LF:%d%n" (count instrumented))
          (printf "LH:%d%n" (count (filter (fn [line] (> (:hit line) 0)) lines)))
          (println "end_of_record"))))

(defn lcov-report
  "Write LCOV report to '${out-dir}/lcov.info'."
  [out-dir forms]
  (let [file (File. out-dir "lcov.info")]
    (.mkdirs (.getParentFile file))
    (with-out-writer file (write-lcov-report forms))
    nil))


;; Java 7 has a much nicer API, but this supports Java 6.
(defn relative-path [target-dir base-dir]
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

(defn html-report [out-dir forms]
  (copy (resource-reader "coverage.css") (File. out-dir "coverage.css"))
  (stats-report (File. out-dir "coverage.txt") forms)
  (doseq [[rel-file file-forms] (group-by :file forms)]
    (let [file     (File. out-dir (str rel-file ".html"))
          rootpath (relative-path (File. out-dir) (.getParentFile file))
          ]
      (.mkdirs (.getParentFile file))
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

(defn html-summary [out-dir forms]
  (let [index (File. out-dir "index.html")
        totalled-stats (total-stats forms)]
    (.mkdirs (File. out-dir))
    (with-out-writer index
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
          (println "</tr>")
          ))
      (println "<tr><td>Totals:</td>")
      (println (td-bar nil))
      (println (td-num (format "%.2f %%" (:percent-forms-covered totalled-stats))))
      (println (td-bar nil))
      (println (td-num (format "%.2f %%" (:percent-lines-covered totalled-stats))))
      (println "   </tr>")
      (println "  </table>")
      (println " </body>")
      (println "</html>"))
    (format "HTML: file://%s" (.getAbsolutePath index))))

(defn coveralls-report [out-dir forms]
  (letfn [(has-env [s] (= (System/getenv s) "true"))
          (service-info [sname job-id-var] [sname (System/getenv job-id-var)])]
    (let [[service job-id]
             (cond
               ;; docs.travis-ci.com/user/ci-environment/
               (has-env "TRAVIS") (service-info "travis-ci" "TRAVIS_JOB_ID")
               ;; circleci.com/docs/environment-variables
               (has-env "CIRCLECI")
                 (service-info "circleci" "CIRCLE_BUILD_NUM")
               ;; bit.ly/semaphoreapp-env-vars
               (has-env "SEMAPHORE") (service-info "semaphore" "REVISION")
               ;; bit.ly/jenkins-env-vars
               (System/getenv "JENKINS_URL")
                 (service-info "jenkins" "BUILD_ID")
               ;; bit.ly/codeship-env-vars
               (= (System/getenv "CI_NAME") "codeship")
                 (service-info "codeship" "CI_BUILD_NUMBER"))
          covdata (map
                    (fn [[file file-forms]]
                      (let [lines (line-stats file-forms)]
                        {:name file
			 :source_digest (md5 (cs/join "\n" (map :text lines)))
			 ;; >0: covered (number of times hit)
                         ;; 0: not covered
			 ;; null: blank
			 :coverage (map #(if (:instrumented? %) (:hit %)) lines)}))
                      (filter (fn [[file _]] file)
                              (group-by :file forms)))]
          (with-out-writer (File. out-dir "coveralls.json")
            (print (json/generate-string {:service_job_id job-id
                                          :service_name service
                                          :source_files covdata}))))))


(defn codecov-report [out-dir forms]
  (println "codecov start")
  (let [data (filter (fn [[file _]] file) (group-by :file forms))
        covdata
        (into {}
              (map
               (fn [[file file-forms]]
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
               data))]
    (with-out-writer (File. out-dir "codecov.json")
      (print (json/generate-string {:coverage covdata})))))

(defn raw-report [out-dir stats covered]
  (with-out-writer (File. (File. out-dir) "raw-data.clj")
    (clojure.pprint/pprint (zipmap (range) covered)))
  (with-out-writer (File. (File. out-dir) "raw-stats.clj")
                   (clojure.pprint/pprint stats)))

(defn summary
  "Create a text summary for output on the command line"
  [forms]
  (let [totalled-stats (total-stats forms)
        namespaces (map (fn [file-stat]
                          (let [libname   (:lib  file-stat)

                                forms     (:forms file-stat)
                                cov-forms (:covered-forms file-stat)
                                instrd    (:instrd-lines  file-stat)
                                covered   (:covered-lines file-stat)
                                partial   (:partial-lines file-stat)]
                            {:name libname
                             :forms_percent (format "%.2f %%" (/ (* 100.0 cov-forms) forms))
                             :lines_percent (format "%.2f %%" (/ (* 100.0 (+ covered partial)) instrd))
                             :forms (/ (* 100.0 cov-forms) forms)
                             :lines (/ (* 100.0 (+ covered partial)) instrd)}))
                        (sort-by :lib (file-stats forms)))
        bad-namespaces (filter #(or (not= 100.0 (:forms %)) (not= 100.0 (:lines %))) namespaces)]

    (str
      (when (< 0 (count bad-namespaces))
        (str
          (with-out-str (clojure.pprint/print-table [:name :forms_percent :lines_percent] bad-namespaces))
          "Files with 100% coverage: "
          (- (count namespaces) (count bad-namespaces))
          "\n"))
      "\nForms covered: "
      (format "%.2f %%" (:percent-forms-covered totalled-stats))
      "\nLines covered: "
      (format "%.2f %%" (:percent-lines-covered totalled-stats))
      "\n")))
