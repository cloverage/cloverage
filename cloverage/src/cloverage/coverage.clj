(ns cloverage.coverage
  (:import [clojure.lang LineNumberingPushbackReader IObj]
           [java.io File InputStreamReader])
  (:use [clojure.java.io :only [reader writer copy]]
        [clojure.tools.cli :only [cli]]
        [cloverage instrument debug])
  (:require [clojure.set :as set]
            [clojure.test :as test]
            [clojure.tools.logging :as log])

  (:gen-class))

(def ^:dynamic *covered*)

;; borrowed from duck-streams
(defmacro with-out-writer
  "Opens a writer on f, binds it to *out*, and evalutes body.
  Anything printed within body will be written to f."
  [f & body]
  `(with-open [stream# (writer ~f)]
     (binding [*out* stream#]
       ~@body)))

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
  `(do
     (cover ~idx)
     ~form))

(defn add-form
  "Adds a structure representing the given form to the *covered* vector."
  [form line-hint]
  (tprnl "Adding form" form "at line" (:line (meta form)) "hint" line-hint)
  (let [file *instrumenting-file*
        line (if (:line (meta form)) (:line (meta form)) line-hint)
        form-info {:form (or (:original (meta form))
                             form)
                   :full-form form
                   :line line
                   :file file}]
    (binding [*print-meta* true]
      (tprn "Parsed form" form)
      (tprn "Adding" form-info))
      (dosync
       (alter *covered* conj form-info)
       (dec (count @*covered*)))))

(defn track-coverage [line-hint form]
  (tprnl "Track coverage called with" form)
  (let [idx   (count @*covered*)
        form# (if (instance? clojure.lang.IObj form)
                (vary-meta form assoc :idx idx)
                form)]
    `(capture ~(add-form form# line-hint) ~form#)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Reporting

(defn- group-by-line [forms]
  (into (sorted-map) (group-by :line forms)))

(defn- postprocess-file [resource forms]
  (with-open [in (reader (resource-reader resource))]
    (let [forms-by-line (group-by-line forms)
          make-rec (fn [line text]
                     (map (partial merge {:text text :line line :file resource})
                          (forms-by-line line [{:line line}])))
          line-nums (next (iterate inc 0))
          lines (into [] (line-seq in))]
      (mapcat make-rec line-nums lines))))

(defn gather-stats [forms]
  (let [forms-by-file (group-by :file forms)]
    (mapcat (partial apply postprocess-file) forms-by-file)))

(defn line-stats [forms]
  (for [[line line-forms] (group-by-line forms)]
    (let [total (count (filter :form line-forms))
          hit   (count (filter :covered line-forms))]
    {:line line
     :text (:text (first line-forms))
     :total total
     :hit   hit
     :covered (and (> total 0) (= total hit))
     :partial (> hit 0)
     :instrumented  (> total 0)
     :blank (empty? (:text (first line-forms)))})))

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
  (with-open [outf (writer file)]
    (binding [*out* outf]
      (printf "Lines Non-Blank Instrumented Covered%n")
      (doseq [file-info (file-stats cov)]
        (apply printf "%5d %9d %7d %10d %s%n"
               (map file-info [:lines :non-blank-lines :instrumented-lines
                               :covered-lines :file]))))))

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
                             (:covered line) "✔"
                             (:partial line) "~"
                             (:instrumented line) "✘"
                             :else           "?")]
            (println prefix (:text line))))))))

(defn replace-spaces [s]
  (.replace s " " "&nbsp;"))

(defn html-report [out-dir forms]
  (copy (resource-reader "coverage.css") (File. out-dir "coverage.css"))
  (stats-report (File. out-dir "coverage.txt") forms)
  (doseq [[rel-file file-forms] (group-by :file forms)]
    (let [file     (File. out-dir (str rel-file ".html"))
          rootpath (.relativize (.. file getParentFile toPath) (.toPath (File. out-dir)))
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
          (let [cls (cond (:blank line) "blank"
                          (:covered line) "covered"
                          (:partial line) "partial"
                          (:instrumented line) "not-covered"
                          :else            "not-tracked")]
            (printf
               "<span class=\"%s\" title=\"%d out of %d forms covered\">
                 %03d&nbsp;&nbsp;%s
                </span><br/>%n" cls (:hit line) (:total line) (:line line)
                (replace-spaces (:text line "&nbsp;")))))
        (println " </body>")
        (println "</html>")))))

(defn collecting-args-parser []
  (let [col (ref [])]
    (fn [val]
      (dosync (alter col conj val)
              @col))))

(defn parse-args [args]
  (cli args ["-o" "--output"]
            ["-t" "--[no-]text"]
            ["-h" "--[no-]html"]
            ["-r" "--[no-]raw"]
            ["-d" "--[no-]debug"]
            ["-x" "--test-ns"
               "Additional test namespace. (can specify multiple times)"
               :default  []
               :parse-fn (collecting-args-parser)]))

(defn -main
  "Produce test coverage report for some namespaces"
  [& args]
  (let [[opts, namespaces] (parse-args args)
        output       (:output opts)
        text?        (:text opts)
        html?        (:html opts)
        raw?         (:raw opts)
        debug?       (:debug opts)
        test-nses    (:test-ns opts)
        ]
    (binding [*covered* (ref [])
              *ns*      (find-ns 'cloverage.coverage)
              *debug*   debug?]
      ;; Load all the namespaces, so that any requires within them
      ;; will not re-load the ns.
      (println test-nses namespaces)
      (when-not (empty? test-nses)
        (apply require (map symbol test-nses))) 
      (apply require (map symbol namespaces))
      (doseq [namespace (map symbol namespaces)]
        (instrument track-coverage namespace))
      (apply test/run-tests (map symbol (concat namespaces test-nses)))
      (when output
        (.mkdir (File. output))
        (let [stats (gather-stats @*covered*)]
          (when text?
            (report output stats))
          (when html?
            (html-report output stats))
          (when raw?
            (with-out-writer (File. (File. output) "covered.clj")
              (binding [*print-meta* true]
                (doall
                  (map prn @*covered*))))
            (with-out-writer (File. (File. output) "coverage.clj")
              (binding [*print-meta* true]
                (doall (map prn stats)))))))))
  nil)
