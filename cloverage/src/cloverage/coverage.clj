(ns cloverage.coverage
  (:import [clojure.lang LineNumberingPushbackReader IObj]
           [java.io File InputStreamReader]
           [java.lang Runtime])
  (:use [clojure.java.io :only [reader writer copy]]
        [clojure.tools.cli :only [cli]]
        [cloverage instrument debug report])
  (:require [clojure.set :as set]
            [clojure.test :as test]
            [clojure.tools.logging :as log])

  (:gen-class))

(def ^:dynamic *covered* (ref []))

(defmacro with-coverage [libs & body]
  `(binding [*covered* (ref [])]
     (println "Capturing code coverage for" ~libs)
     (doseq [lib# ~libs]
       (instrument track-coverage lib#))
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
  (let [lib  *instrumenting-file*
        file (resource-path lib)
        line (or (:line (meta form)) line-hint)
        form-info {:form (or (:original (meta form))
                             form)
                   :full-form form
                   :line line
                   :lib  lib
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
        form' (if (instance? clojure.lang.IObj form)
                (vary-meta form assoc :idx idx)
                form)]
    `(capture ~(add-form form' line-hint) ~form')))

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
            ["-n" "--[no-]nop" "Instrument with noops." :default false]
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
        nops?        (:nop opts)
        test-nses    (:test-ns opts)
        start        (System/currentTimeMillis)
        ]
    (binding [*covered* (ref [])
              *ns*      (find-ns 'cloverage.coverage)
              *debug*   debug?]
      (println test-nses namespaces)
      (doseq [namespace (map symbol namespaces)]
        ;; load the ns to prevent it from being reloaded when it's required
        (require namespace)
        (if nops?
          (instrument-nop namespace)
          (instrument track-coverage namespace)))
      (println "Done instrumenting namespaces.")
      (when-not (empty? test-nses)
        (apply require (map symbol test-nses)))
      (apply test/run-tests (map symbol (concat namespaces test-nses)))
      (when output
        (.mkdir (File. output))
        (let [stats (gather-stats @*covered*)]
          (when text?
            (text-report output stats))
          (when html?
            (html-summary output stats)
            (html-report output stats))
          (when raw?
            (raw-report output stats @*covered*))))))
  (shutdown-agents)
  nil)
