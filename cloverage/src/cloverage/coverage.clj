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
  (cli args ["-o" "--output"]
            ["-t" "--[no-]text"
               "Produce a text report." :default false]
            ["-h" "--[no-]html"
               "Produce an HTML report." :default true]
            ["-r" "--[no-]raw"
               "Output raw coverage data." :default false]
            ["-d" "--[no-]debug"
               "Output debugging information to stdout." :default false]
            ["-n" "--[no-]nop" "Instrument with noops." :default false]
            ["-p" "--pattern"
               "Regex for instrumented namespaces, can specify multiple."
               :default  []
               :parse-fn (collecting-args-parser)]
            ["--test-pattern"
               "Regex for test namespaces, can specify multiple."
               :default []
               :parse-fn (collecting-args-parser)]
            ["-x" "--test-ns"
               "Additional test namespace. (can specify multiple times)"
               :default  []
               :parse-fn (collecting-args-parser)]))

(defn mark-loaded [namespace]
  (in-ns 'clojure.core)
  (eval `(dosync (alter clojure.core/*loaded-libs* conj '~namespace))) 
  (in-ns 'cloverage.coverage)
  )

(defn find-nses [regexs]
  (if-not (empty? regexs)
    (filter (apply some-fn (map #(fn [sym] (re-matches % (name sym))) regexs))
            (blt/namespaces-on-classpath))
    []))

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
        add-test-nses (:test-ns opts)
        ns-regexs     (map re-pattern (:pattern opts)) 
        test-regexs   (map re-pattern (:test-pattern opts)) 
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
        (mark-loaded namespace))
        ;; mark the ns as loaded
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
            (raw-report output stats @*covered*))))
      (println "Produced output.")))
  (shutdown-agents)
  nil)
