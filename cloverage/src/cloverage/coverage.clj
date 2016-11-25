(ns cloverage.coverage
  (:gen-class)
  (:require [bultitude.core :as blt]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :as test]
            [clojure.tools.cli :as cli]
            [clojure.test.junit :as junit]
            [clojure.tools.logging :as log]
            [cloverage.debug :as debug]
            [cloverage.dependency :as dep]
            [cloverage.instrument :as inst]
            [cloverage.report :as rep]
            [cloverage.report.console :as console]
            [cloverage.report.coveralls :as coveralls]
            [cloverage.report.codecov :as codecov]
            [cloverage.report.emma-xml :as emma-xml]
            [cloverage.report.html :as html]
            [cloverage.report.lcov :as lcov]
            [cloverage.report.raw :as raw]
            [cloverage.report.text :as text]
            [cloverage.source :as src])
  (:import clojure.lang.IObj))

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
           ["--[no-]junit"
            "Output test results as junit xml file. Supported in :clojure.test runner" :default false]
           ["--[no-]raw"
            "Output raw coverage data (for debugging)." :default false]
           ["--[no-]summary"
            "Prints a summary" :default true]
           ["--fail-threshold"
            "Sets the percentage threshold at which cloverage will abort the build. Default: 0%"
            :default 0
            :parse-fn #(Integer/parseInt %)]
           ["--low-watermark"
            "Sets the low watermark percentage (valid values 0..100). Default: 50%"
            :default 50
            :parse-fn #(Integer/parseInt %)]
           ["--high-watermark"
            "Sets the high watermark percentage (valid values 0..100). Default: 80%"
            :default 80
            :parse-fn #(Integer/parseInt %)]
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

(defmulti runner-fn :runner)

(defmethod runner-fn :midje [_]
  (if-let [f (resolve-var 'midje.repl/load-facts)]
    (fn [nses]
      {:errors (:failures (apply f nses))})
    (throw (RuntimeException. "Failed to load Midje."))))

(defmethod runner-fn :clojure.test [{:keys [junit output] :as opts}]
  (fn [nses]
    (let [run-tests (fn []
                      (apply require (map symbol nses))
                      {:errors (reduce + ((juxt :error :fail)
                                          (apply test/run-tests nses)))})]
      (if junit
        (do
          (.mkdirs (io/file output))
          (binding [test/*test-out* (io/writer (io/file output "junit.xml"))]
            (junit/with-junit-output (run-tests))))
        (run-tests)))))

(defmethod runner-fn :default [_]
  (throw (IllegalArgumentException.
          "Currently supported runners are only `clojure.test` and `midje`.")))

(defn- coverage-under? [forms failure-threshold]
  (when (pos? failure-threshold)
    (let [pct-covered (apply min (vals (rep/total-stats forms)))
          failed? (< pct-covered failure-threshold)]
      (when failed?
        (println "Failing build as coverage is below threshold of" failure-threshold "%"))
      failed?)))

(defn -main
  "Produce test coverage report for some namespaces"
  [& args]
  (let [[opts add-nses help] (parse-args args)
        ^String output  (:output opts)
        text?           (:text opts)
        html?           (:html opts)
        raw?            (:raw opts)
        emma-xml?       (:emma-xml opts)
        junit?          (:junit opts)
        lcov?           (:lcov opts)
        codecov?        (:codecov opts)
        coveralls?      (:coveralls opts)
        summary?        (:summary opts)
        fail-threshold  (:fail-threshold opts)
        low-watermark   (:low-watermark opts)
        high-watermark  (:high-watermark opts)
        debug?          (:debug opts)
        nops?           (:nop opts)
        help?           (:help opts)
        extra-test-nses (:extra-test-ns opts)
        ns-regexs       (map re-pattern (:ns-regex opts))
        test-regexs     (map re-pattern (:test-ns-regex opts))
        exclude-regex   (map re-pattern (:ns-exclude-regex opts))
        ns-path         (:src-ns-path opts)
        runner          (:runner opts)
        test-ns-path    (:test-ns-path opts)
        namespaces      (set/difference
                         (set (concat add-nses (find-nses ns-path ns-regexs)))
                         (set (find-nses ns-path exclude-regex)))
        test-nses       (concat extra-test-nses (find-nses test-ns-path test-regexs))
        ordered-nses    (dep/in-dependency-order (map symbol namespaces))]
    (if help?
      (println help)
      (binding [*ns*      (find-ns 'cloverage.coverage)
                debug/*debug*   debug?]

        (println "Loading namespaces: " (apply list namespaces))
        (println "Test namespaces: " test-nses)

        (if (empty? ordered-nses)
          (throw (RuntimeException. "Cannot instrument namespaces; there is a cyclic depdendency"))
          (doseq [namespace ordered-nses]
            (binding [*instrumented-ns* namespace]
              (if nops?
                (inst/instrument #'inst/nop namespace)
                (inst/instrument #'track-coverage namespace)))
            (println "Loaded " namespace " .")
            ;; mark the ns as loaded
            (mark-loaded namespace)))

        (println "Instrumented namespaces.")
        (let [test-result (when-not (empty? test-nses)
                            (if (and junit?
                                     (not (= runner :clojure.test)))
                              (throw (RuntimeException. "Junit output only supported for clojure.test at present"))
                              ((runner-fn opts) (map symbol test-nses))))
              forms       (rep/gather-stats @*covered*)
              ;; sum up errors as in lein test
              errors      (when test-result
                            (:errors test-result))
              exit-code   (cond
                            (not test-result) -1
                            (> errors 128)    -2
                            (coverage-under? forms fail-threshold) -3
                            :else             errors)]
          (println "Ran tests.")
          (when output
            (.mkdirs (io/file output))
            (when text? (text/report output forms))
            (when html? (html/report output forms))
            (when emma-xml? (emma-xml/report output forms))
            (when lcov? (lcov/report output forms))
            (when raw? (raw/report output forms @*covered*))
            (when codecov? (codecov/report output forms))
            (when coveralls? (coveralls/report output forms))
            (when summary? (console/summary forms low-watermark high-watermark)))
          (if *exit-after-test*
            (do (shutdown-agents)
                (System/exit exit-code))
            exit-code))))))
