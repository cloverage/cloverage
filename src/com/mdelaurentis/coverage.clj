(ns com.mdelaurentis.coverage
  (:import [clojure.lang LineNumberingPushbackReader IObj]
           [java.io File InputStreamReader])
  (:use [clojure.contrib.duck-streams :only [reader with-out-writer copy]]
        [clojure.contrib.command-line :only [with-command-line]]
        [clojure.contrib.seq-utils :only [group-by]]
        [clojure.contrib.except]
        [com.mdelaurentis instrument])
  (:require [clojure.set :as set]
            [clojure.test :as test]
            [clojure.contrib.logging :as log])

  (:gen-class))

(def *covered*)

(defmacro with-coverage [libs & body]
  `(binding [*covered* (ref [])]
     (println "Capturing code coverage for" ~libs)
     (doseq [lib# ~libs]
       (instrument lib#))
     ~@body
     (gather-stats @*covered*)))

(defn cover [idx]
  "Mark the given file and line in as having been covered."
  (dosync 
   (if (contains? @*covered* idx)
     (alter *covered* assoc-in [idx :covered] true)
     (log/warn (str "Couldn't track coverage for form with index " idx ".")))))

(defmacro capture 
  "Eval the given form and record that the given line on the given
  files was run."
  [idx form]
  (let [text (with-out-str (prn form))]
    `(do 
       (cover ~idx)
       ~form)))

(defn add-form 
  "Adds a structure representing the given form to the *covered* vector."
  [form]
  (let [file *instrumenting-file*
        form-info {:form (or (:original (meta form))
                             form)
                   :line (:line (meta form))
                   :file file}]
  (binding [*print-meta* true]
    #_(prn "Adding" form-info)
    #_(newline))
    (dosync 
     (alter *covered* conj form-info)
     (dec (count @*covered*)))))

(defn track-coverage [form]
  #_(println "Track coverage called with" form)
  `(capture ~(add-form form) ~form))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Reporting

(defn- postprocess-file [resource forms]
  (with-open [in (reader (resource-reader resource))]
    (let [forms-by-line (group-by :line forms)
          make-rec (fn [line text]
                     (map (partial merge {:text text :line line :file resource})
                          (forms-by-line line [{:line line}])))
          line-nums (next (iterate inc 0))
          lines (into [] (line-seq in))]
      (mapcat make-rec line-nums lines))))

(defn- gather-stats [forms]
  (let [forms-by-file (group-by :file forms)]
    (mapcat (partial apply postprocess-file) forms-by-file)))

(defn line-stats [forms]
  (for [[line line-forms] (group-by :line forms)]
    {:line line
     :text (:text (first line-forms))
     :covered (some :covered line-forms)
     :instrumented (some :form line-forms)
     :blank (empty? (:text (first line-forms)))}))

(defn file-stats [forms]
  (for [[file file-forms] (group-by :file forms)
        :let [lines (line-stats file-forms)]]
    {:file file
     :lines (count lines)
     :non-blank-lines (count (remove :blank lines))
     :instrumented-lines (count (filter :instrumented lines))
     :covered-lines (count (filter :covered lines))}))

(defn stats-report [file cov]
  (.mkdirs (.getParentFile file))
  (with-out-writer file
    (printf "Lines Non-Blank Instrumented Covered%n")
    (doseq [file-info (file-stats cov)]
      (apply printf "%5d %9d %7d %10d %s%n"
             (map file-info [:lines :non-blank-lines :instrumented-lines
                             :covered-lines :file])))))

(defn report [out-dir forms]
  (stats-report (File. out-dir "coverage.txt") forms)
  (doseq [[file file-forms] (group-by :file forms)
          :when file]
    (println "Reporting on" file)
    (let [file (File. out-dir file)]
      (.mkdirs (.getParentFile file))
      (with-out-writer file
        (doseq [line (line-stats file-forms)]
          (let [prefix (cond (:blank line)   " "
                             (:covered line) "+"
                             (:instrumented line) "-"
                             :else           "?")]
            (println prefix (:text line))))))))

(defn replace-spaces [s]
  (.replace s " " "&nbsp;"))

(defn html-report [out-dir cov]
  (copy (resource-reader "coverage.css") (File. out-dir "coverage.css"))
  (stats-report (File. out-dir "coverage.txt") cov)
  (doseq [{rel-file :file, content :content} cov]
    (let [file (File. out-dir (str rel-file ".html"))]
      (.mkdirs (.getParentFile file))
      (with-out-writer file
        (println "<html>")
        (println " <head>")
        (println "  <link rel=\"stylesheet\" href=\"../../coverage.css\"/>")
        (println "  <title>" rel-file "</title>")
        (println " </head>")
        (println " <body>")
        (doseq [info content]
          (let [cls (cond (empty? (:forms info)) "blank"
                          (some :covered (:forms info)) "covered"
                          :else            "not-covered")]
            (printf "<span class=\"%s\">%s</span><br/>%n" cls (replace-spaces (:text info "")))))
        (println " </body>")
        (println "</html>")))))


(defn -main [& args]
  (with-command-line args
    "Produce test coverage report for some namespaces"
    [[output o "Output directory"]
     [text?   t "Produce text file reports?"]
     [html?   h "Produce html reports?"]
     [raw?    r "Output the raw coverage information?"]
     namespaces]
    
    (binding [*covered* (ref [])
              *ns* (find-ns 'com.mdelaurentis.coverage)]
      (doseq [namespace (map symbol namespaces)]
        (instrument track-coverage namespace))
      (apply test/run-tests (map symbol namespaces))
      (when output
        (.mkdir (File. output))
        (let [stats (gather-stats @*covered*)]
          (when text?
            (report output stats))
          (when html?
            (html-report output stats))
          (when raw?
            (with-out-writer (File. (File. output) "coverage.clj")
              (prn stats))))))))