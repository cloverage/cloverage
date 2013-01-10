(ns cloverage.coverage
  (:import [clojure.lang LineNumberingPushbackReader IObj]
           [java.io File InputStreamReader]
           [java.lang Runtime])
  (:use [clojure.java.io :only [reader writer copy]]
        [clojure.tools.cli :only [cli]]
        [cloverage source instrument debug report dependency])
  (:require [clojure.set :as set]
            [clojure.test :as test]
            [clojure.tools.logging :as log]
            [bultitude.core :as blt])
  (:gen-class))

(def ^:dynamic *instrumented-ns*) ;; currently instrumented ns
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
     (log/warn (str "Couldn't track coverage for form with index " idx
                    " covered has " (count @*covered*) ".")))))

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
  (let [lib  *instrumented-ns*
        file (resource-path lib)
        line (or (:line (meta form)) line-hint)
        form-info {:form (or (:original (meta form))
                             form)
                   :full-form form
                   :tracked true
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
  (cli args
       ["-o" "--output" "Output directory." :default "target/coverage"]
       ["--[no-]text"
        "Produce a text report." :default false]
       ["--[no-]html"
        "Produce an HTML report." :default true]
       ["--[no-]raw"
        "Output raw coverage data (for debugging)." :default false]
       ["-d" "--[no-]debug"
        "Output debugging information to stdout." :default false]
       ["--[no-]nop" "Instrument with noops." :default false]
       ["-n" "--ns-regex"
        "Regex for instrumented namespaces (can be repeated)."
        :default  []
        :parse-fn (collecting-args-parser)]
       ["-t" "--test-ns-regex"
        "Regex for test namespaces (can be repeated)."
        :default []
        :parse-fn (collecting-args-parser)]
       ["-x" "--extra-test-ns"
        "Additional test namespace (string) to add (can be repeated)"
        :default  []
        :parse-fn (collecting-args-parser)]))

(defn mark-loaded [namespace]
  (binding [*ns* (find-ns 'clojure.core)]
    (eval `(dosync (alter clojure.core/*loaded-libs* conj '~namespace)))))

(defn find-nses [patterns]
  (for [ns (map name (blt/namespaces-on-classpath))
        :when (some #(re-matches % ns) patterns)]
    ns))

(defn -main
  "Produce test coverage report for some namespaces"
  [& args]
  (let [[opts, add-nses] (parse-args args)
        output        (:output opts)
        text?         (:text opts)
        html?         (:html opts)
        raw?          (:raw opts)
        debug?        (:debug opts)
        nops?         (:nop opts)
        add-test-nses (:extra-test-ns opts)
        ns-regexs     (map re-pattern (:ns-regexp opts))
        test-regexs   (map re-pattern (:test-ns-regexp opts))
        start         (System/currentTimeMillis)
        test-nses     (concat add-test-nses (find-nses test-regexs))
        namespaces    (concat add-nses      (find-nses ns-regexs))
        ]
    (binding [*ns*      (find-ns 'cloverage.coverage)
              *debug*   debug?]
      (println "Loading namespaces: " namespaces)
      (println "Test namespaces: " test-nses)
      (doseq [namespace (in-dependency-order (map symbol namespaces))]
        (binding [*instrumented-ns* namespace]
          (if nops?
            (instrument nop namespace)
            (instrument track-coverage namespace)))
        (println "Loaded " namespace " .")
        ;; mark the ns as loaded
        (mark-loaded namespace))
      (println "Instrumented namespaces.")
      (when-not (empty? test-nses)
        (let [test-syms (map symbol test-nses)]
          (apply require (map symbol test-nses))
          (apply test/run-tests (map symbol test-nses))))
      (println "Ran tests.")
      (when output
        (.mkdir (File. output))
        (let [stats (gather-stats @*covered*)]
          (when text?
            (text-report output stats))
          (when html?
            (html-summary output stats)
            (html-report output stats))
          (when raw?
            (raw-report output stats @*covered*)))
        (println "Produced output in" (.getAbsolutePath (File. output)) "."))))
  (shutdown-agents)
  nil)
