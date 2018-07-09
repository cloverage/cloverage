(ns cloverage.coverage
  (:gen-class)
  (:require [bultitude.core :as blt]
            [clojure.java.io :as io]
            [clojure.test :as test]
            [clojure.test.junit :as junit]
            [clojure.tools.logging :as log]
            [cloverage.args :as args]
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
  (:import (java.io FileNotFoundException)
           (clojure.lang IObj)))

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
  (let [lib       *instrumented-ns*
        file      (src/resource-path lib)
        line      (or (:line (meta form)) line-hint)
        form-info {:form      (or (:original (meta form))
                                  form)
                   :full-form form
                   :tracked   true
                   :line      line
                   :lib       lib
                   :file      file}]
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
        form' (if (instance? IObj form)
                (vary-meta form assoc :idx idx)
                form)]
    `(capture ~(add-form form' line-hint) ~form')))

(defn mark-loaded [namespace]
  (binding [*ns* (find-ns 'clojure.core)]
    (eval `(dosync (alter clojure.core/*loaded-libs* conj '~namespace)))))

(defn remove-nses
  [namespaces regex-patterns]
  (debug/tprn "Removing" regex-patterns)
  (let [v (remove (fn [ns] (some #(re-matches % ns) regex-patterns)) namespaces)]
    (debug/tprn "Out: " v)
    v))

(defn find-nses
  "Given ns-paths and regex-patterns returns:
  * empty sequence when ns-paths is empty and regex-patterns is empty
  * all namespaces on all ns-paths (if regex-patterns is empty)
  * all namespaces on the classpath that match any of the regex-patterns (if ns-paths is empty)
  * namespaces on ns-paths that match any of the regex-patterns"
  [ns-paths regex-patterns]
  (debug/tprn "find:" {:ns-paths       ns-paths
                       :regex-patterns regex-patterns})
  (let [namespaces (map name
                        (cond
                          (and (empty? ns-paths) (empty? regex-patterns)) '()
                          (empty? ns-paths) (blt/namespaces-on-classpath)
                          :else (mapcat #(blt/namespaces-on-classpath :classpath %) ns-paths)))]
    (debug/tprn "found:" {:namespaces     namespaces
                          :ns-paths       ns-paths
                          :regex-patterns regex-patterns})
    (if (seq regex-patterns)
      (filter (fn [ns] (some #(re-matches % ns) regex-patterns)) namespaces)
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

(defmethod runner-fn :clojure.test [{:keys [junit? output] :as opts}]
  (fn [nses]
    (let [run-tests (fn []
                      (apply require (map symbol nses))
                      {:errors (reduce + ((juxt :error :fail)
                                          (apply test/run-tests nses)))})]
      (if junit?
        (do
          (.mkdirs (io/file output))
          (binding [test/*test-out* (io/writer (io/file output "junit.xml"))]
            (junit/with-junit-output (run-tests))))
        (run-tests)))))

(defmethod runner-fn :default [_]
  (throw (IllegalArgumentException.
          "Runner not found. Built-in runners are `clojure.test` and `midje`.")))

(defn- coverage-under? [forms failure-threshold]
  (when (pos? failure-threshold)
    (let [pct-covered (apply min (vals (rep/total-stats forms)))
          failed?     (< pct-covered failure-threshold)]
      (when failed?
        (println "Failing build as coverage is below threshold of" failure-threshold "%"))
      failed?)))

(defn run-main
  [[{:keys [debug?] :as opts} add-nses help]]
  (binding [*ns*          (find-ns 'cloverage.coverage)
            debug/*debug* debug?]
    (let [^String output (:output opts)
          {:keys [text?
                  html?
                  raw?
                  emma-xml?
                  junit?
                  lcov?
                  codecov?
                  coveralls?
                  summary?
                  fail-threshold
                  low-watermark
                  high-watermark
                  nop?
                  extra-test-ns
                  help?
                  ns-regex
                  test-ns-regex
                  ns-exclude-regex
                  src-ns-path
                  runner
                  test-ns-path]} opts
          include        (-> src-ns-path
                             (find-nses ns-regex)
                             (remove-nses ns-exclude-regex))
          namespaces     (concat add-nses include)
          test-nses      (concat extra-test-ns (find-nses test-ns-path test-ns-regex))
          ordered-nses   (dep/in-dependency-order (map symbol namespaces))]
      (if help?
        (println help)
        (do
          (println "Loading namespaces: " (apply list namespaces))
          (println "Test namespaces: " test-nses)

          (if (empty? ordered-nses)
            (throw (RuntimeException. "Cannot instrument namespaces; there is a cyclic dependency"))
            (doseq [namespace ordered-nses]
              (binding [*instrumented-ns* namespace]
                (if nop?
                  (inst/instrument #'inst/nop namespace)
                  (inst/instrument #'track-coverage namespace)))
              (println "Loaded " namespace " .")
              ;; mark the ns as loaded
              (mark-loaded namespace)))

          (println "Instrumented namespaces.")
          ;; load runner multimethod definition from other dependencies
          (when-not (#{:clojure.test :midje} runner)
            (try (require (symbol (format "%s.cloverage" (name runner))))
                 (catch FileNotFoundException _)))
          (let [test-result (when (seq test-nses)
                              (if (and junit? (not= runner :clojure.test))
                                (throw (RuntimeException.
                                        "Junit output only supported for clojure.test at present"))
                                ((runner-fn opts) (map symbol test-nses))))
                forms       (rep/gather-stats @*covered*)
                ;; sum up errors as in lein test
                errors      (when test-result
                              (:errors test-result))
                exit-code   (cond
                              (not test-result) -1
                              (> errors 128) -2
                              (coverage-under? forms fail-threshold) -3
                              :else errors)]
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
              exit-code)))))))

(defn run-project [project-opts & args]
  (try
    (-> args
        (args/parse-args project-opts)
        (run-main))
    (catch Exception e
      (.printStackTrace e)
      (throw e))))

(defn -main
  "Produce test coverage report for some namespaces"
  [& args]
  (-> args
      (args/parse-args {})
      (run-main)))
