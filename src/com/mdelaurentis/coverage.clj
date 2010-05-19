(ns com.mdelaurentis.coverage
  (:import [clojure.lang LineNumberingPushbackReader IObj]
           [java.io File InputStreamReader])
  (:use [clojure.contrib.duck-streams :only [reader with-out-writer copy]]
        [clojure.contrib.command-line :only [with-command-line]]
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

(defn gather-stats [cov]
  (let [indexed (set/index cov [:file :line])]
    (for [file (filter #(not (= "NO_SOURCE_FILE" %))
                       (distinct (map :file (keys indexed))))]
      (do
        (with-open [in (LineNumberingPushbackReader. (resource-reader file))]
          (loop [forms nil]
            (if-let [text (.readLine in)]

              ;; If we're still reading lines, add a line-info
              ;; structure for this line to our list and recur
              (let [line (dec (.getLineNumber in))
                    info  {:line line
                           :file file
                           :text text
                           :forms (indexed {:file file :line line})}]
                (recur (conj forms info)))

              ;; Otherwise return a map with this file and the info
              ;; for all the lines
              {:file file
               :content (apply vector nil (reverse forms))})))))))

(defn covered [line-info]
  (some :covered (:forms line-info)))

(defn line-has-forms? [line-info]
  (not-empty (:forms line-info)))

(defn instrumented [line-info]
  (when (not-empty (:forms line-info))
    line-info))

(defn blank [line-info]
  (when (empty? (:text line-info))
    line-info))

(defn stats-report [file cov]
  (.mkdirs (.getParentFile file))
  (with-out-writer file
    (printf "Lines Non-Blank Instrumented Covered%n")
    (doseq [{rel-file :file, content :content} cov]
      (printf "%5d %9d %7d %10d %s%n" 
              (count content)
              (count (remove blank content))
              (count (filter instrumented content))
              (count (filter covered content))
              rel-file))))

(defn report [out-dir cov]
  (stats-report (File. out-dir "coverage.txt") cov)
  (doseq [{rel-file :file, content :content} cov]
    (let [file (File. out-dir rel-file)]
      (.mkdirs (.getParentFile file))
      (with-out-writer file
        (doseq [line-info content]
          (let [prefix (cond (not (line-has-forms? line-info)) " "
                             (covered line-info) "+"
                             :else            "-")]
            (println prefix (:text line-info))))))))

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
            (prn stats)))))))