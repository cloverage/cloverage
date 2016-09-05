(ns cloverage.coverage
  (:gen-class)
  (:require [bultitude.core :as blt]
            [clojure.set :as set]
            [clojure.test :as test]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [cloverage.debug :as debug]
            [cloverage.dependency :as dep]
            [cloverage.instrument :as inst]
            [cloverage.report :as rep]
            [cloverage.source :as src])
  (:import clojure.lang.IObj
           java.io.File))

(def ^:dynamic *instrumented-ns*) ;; currently instrumented ns
(def ^:dynamic *covered* (atom []))
(def ^:dynamic *exit-after-test* true)

(defmacro with-coverage [libs & body]
  `(binding [*covered* (atom [])]
     (println "Capturing code coverage for" ~libs)
     (doseq [lib# ~libs]
       (instrument #'track-coverage lib#))
     ~@body
     (gather-stats @*covered*)))

(defn cover
  "Mark the given file and line in as having been covered."
  [idx]
  (let [covered (swap! *covered* #(if-let [{:keys [hits] :as data} (nth % idx nil)]
                                    (assoc % idx (assoc data :covered true :hits (inc (or hits 0))))
                                    %))]
    (when-not (nth covered idx nil)
      (log/warn (str "Couldn't track coverage for form with index " idx
                     " covered has " (count covered) ".")))))

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
  (debug/tprnl "Adding form" form "at line" (:line (meta form)) "hint" line-hint)
  (let [lib  *instrumented-ns*
        file (src/resource-path lib)
        line (or (:line (meta form)) line-hint)
        form-info {:form (or (:original (meta form))
                             form)
                   :full-form form
                   :tracked true
                   :line line
                   :lib  lib
                   :file file}]
    (binding [*print-meta* true]
      (debug/tprn "Parsed form" form)
      (debug/tprn "Adding" form-info))
    (->
     (swap! *covered* conj form-info)
     count
     dec)))

(defn track-coverage [line-hint form]
  (debug/tprnl "Track coverage called with" form)
  (let [idx   (count @*covered*)
        form' (if (instance? clojure.lang.IObj form)
                (vary-meta form assoc :idx idx)
                form)]
    `(capture ~(add-form form' line-hint) ~form')))

(defn collecting-args-parser []
  (let [col (atom [])]
    (fn [val]
      (swap! col conj val))))

(defn- parse-kw-str [s]
  (let [s (name s)
        s (if (and s (.startsWith s ":")) (subs s 1) s)]
    (keyword s)))

(defn parse-args [args]
  (cli/cli args
       ["-o" "--output" "Output directory." :default "target/coverage"]
       ["--[no-]text"
        "Produce a text report." :default false]
       ["--[no-]html"
        "Produce an HTML report." :default true]
       ["--[no-]emma-xml"
        "Produce an EMMA XML report. [emma.sourceforge.net]" :default false]
       ["--[no-]lcov"
        "Produce a lcov/gcov report." :default false]
       ["--[no-]codecov"
        "Generate a JSON report for Codecov.io" :default false]
       ["--[no-]coveralls"
        "Send a JSON report to Coveralls if on a CI server" :default false]
       ["--[no-]raw"
        "Output raw coverage data (for debugging)." :default false]
       ["--[no-]summary"
        "Prints a summary" :default true]
       ["-d" "--[no-]debug"
        "Output debugging information to stdout." :default false]
       ["-r" "--runner"
        "Specify which test runner to use. Currently supported runners are `clojure.test` and `midje`."
        :default :clojure.test
        :parse-fn parse-kw-str]
       ["--[no-]nop" "Instrument with noops." :default false]
       ["-n" "--ns-regex"
        "Regex for instrumented namespaces (can be repeated)."
        :default  []
        :parse-fn (collecting-args-parser)]
       ["-e" "--ns-exclude-regex"
        "Regex for namespaces not to be instrumented (can be repeated)."
        :default  []
        :parse-fn (collecting-args-parser)]
       ["-t" "--test-ns-regex"
        "Regex for test namespaces (can be repeated)."
        :default []
        :parse-fn (collecting-args-parser)]
       ["-p" "--src-ns-path"
        "Path (string) to directory containing source code namespaces."
        :default nil]
       ["-s" "--test-ns-path"
        "Path (string) to directory containing test namespaces."
        :default nil]
       ["-x" "--extra-test-ns"
        "Additional test namespace (string) to add (can be repeated)."
        :default  []
        :parse-fn (collecting-args-parser)]
       ["-h" "--help" "Show help." :default false :flag true]))

(defn mark-loaded [namespace]
  (binding [*ns* (find-ns 'clojure.core)]
    (eval `(dosync (alter clojure.core/*loaded-libs* conj '~namespace)))))

(defn find-nses [ns-path regex-patterns]
  "Given ns-path and regex-patterns returns:
  * empty sequence when ns-path is nil and regex-patterns is empty
  * all namespaces on ns-path (if regex-patterns is empty)
  * all namespaces on the classpath that match any of the regex-patterns (if ns-path is nil)
  * namespaces on ns-path that match any of the regex-patterns"
  (let [namespaces (->> (cond
                          (and (nil? ns-path) (empty? regex-patterns)) '()
                          (nil? ns-path) (blt/namespaces-on-classpath)
                          :else (blt/namespaces-on-classpath :classpath ns-path))
                        (map name))]
    (if (seq regex-patterns)
      (filter (fn [namespace] (some #(re-matches % namespace) regex-patterns))
              namespaces)
      namespaces)))

(defn- resolve-var [sym]
  (let [ns (namespace (symbol sym))
        ns (when ns (symbol ns))]
    (when ns
      (require ns))
    (ns-resolve (or ns *ns*)
                (symbol (name sym)))))

(defmulti runner-fn identity)

(defmethod runner-fn :midje [_]
  (if-let [f (resolve-var 'midje.repl/load-facts)]
    (fn [nses]
      {:errors (:failures (apply f nses))})
    (throw (RuntimeException. "Failed to load Midje."))))

(defmethod runner-fn :clojure.test [_]
  (fn [nses]
    (apply require (map symbol nses))
    {:errors (reduce + ((juxt :error :fail)
                        (apply test/run-tests nses)))}))

(defmethod runner-fn :default [_]
  (throw (IllegalArgumentException.
          "Currently supported runners are only `clojure.test` and `midje`.")))

(defn -main
  "Produce test coverage report for some namespaces"
  [& args]
  (let [[opts add-nses help] (parse-args args)
        output        (:output opts)
        text?         (:text opts)
        html?         (:html opts)
        raw?          (:raw opts)
        emma-xml?     (:emma-xml opts)
        lcov?         (:lcov opts)
        codecov?      (:codecov opts)
        coveralls?    (:coveralls opts)
        summary?      (:summary opts)
        debug?        (:debug opts)
        nops?         (:nop opts)
        help?         (:help opts)
        add-test-nses (:extra-test-ns opts)
        ns-regexs     (map re-pattern (:ns-regex opts))
        test-regexs   (map re-pattern (:test-ns-regex opts))
        exclude-regex (map re-pattern (:ns-exclude-regex opts))
        ns-path       (:src-ns-path opts)
        test-ns-path  (:test-ns-path opts)
        runner        (runner-fn (:runner opts))
        start         (System/currentTimeMillis)
        namespaces    (set/difference
                        (into #{}
                              (concat add-nses
                                      (find-nses ns-path ns-regexs)))
                        (into #{} (find-nses ns-path exclude-regex)))
        test-nses     (concat add-test-nses (find-nses test-ns-path test-regexs))]
    (if help?
      (println help)
      (binding [*ns*      (find-ns 'cloverage.coverage)
                debug/*debug*   debug?]
        (println "Loading namespaces: " (apply list namespaces))
        (println "Test namespaces: " test-nses)
        (doseq [namespace (dep/in-dependency-order (map symbol namespaces))]
          (binding [*instrumented-ns* namespace]
            (if nops?
              (inst/instrument #'inst/nop namespace)
              (inst/instrument #'track-coverage namespace)))
          (println "Loaded " namespace " .")
          ;; mark the ns as loaded
          (mark-loaded namespace))
        (println "Instrumented namespaces.")
        (let [test-result (when-not (empty? test-nses)
                            (let [test-syms (map symbol test-nses)]
                              (runner test-syms)))
              ;; sum up errors as in lein test
              errors      (when test-result
                            (:errors test-result))
              exit-code   (cond
                            (not test-result) -1
                            (> errors 128)    -2
                            :else             errors)]
          (println "Ran tests.")
          (when output
            (.mkdir (File. output))
            (let [stats (rep/gather-stats @*covered*)
                  results [(when text? (rep/text-report output stats))
                           (when html? (rep/html-report output stats)
                             (rep/html-summary output stats))
                           (when emma-xml? (rep/emma-xml-report output stats))
                           (when lcov? (rep/lcov-report output stats))
                           (when raw? (rep/raw-report output stats @*covered*))
                           (when codecov? (rep/codecov-report output stats))
                           (when coveralls? (rep/coveralls-report output stats))
                           (when summary? (rep/summary stats))]]

              (println "Produced output in" (.getAbsolutePath (File. output)) ".")
              (doseq [r results] (when r (println r)))))
          (if *exit-after-test*
            (do (shutdown-agents)
                (System/exit exit-code))
            exit-code))))))
