(ns cloverage.coverage
  (:gen-class)
  (:require [clojure.java.classpath :as cp]
            [clojure.java.io :as io]
            [clojure.test :as test]
            [clojure.test.junit :as junit]
            [clojure.tools.namespace.find :as ns-find]
            [cloverage.args :as args]
            [cloverage.debug :as debug]
            [cloverage.dependency :as dep]
            [cloverage.instrument :as inst]
            [cloverage.report :as rep]
            [cloverage.report.codecov :as codecov]
            [cloverage.report.console :as console]
            [cloverage.report.coveralls :as coveralls]
            [cloverage.report.emma-xml :as emma-xml]
            [cloverage.report.html :as html]
            [cloverage.report.lcov :as lcov]
            [cloverage.report.raw :as raw]
            [cloverage.report.text :as text]
            [cloverage.source :as src])
  (:import clojure.lang.IDeref
           java.io.FileNotFoundException
           java.util.concurrent.atomic.AtomicInteger))

(def ^:dynamic *instrumented-ns*) ;; currently instrumented ns
(def ^:dynamic *covered* (atom []))
(def ^:dynamic *exit-after-test* true)

;; The following form is basically just taken from leiningen.test

(defn form-for-suppressing-unselected-tests
  [namespaces selectors test-func]
  (let [copy-meta (fn [var from-key to-key]
                    (when-let [x (get (meta var) from-key)]
                      (alter-meta! var #(-> % (assoc to-key x) (dissoc from-key)))))
        vars      (when (seq selectors)
                    ;; need to manually require namespaces to make sure they've been
                    ;; loaded before test runner has a chance to run
                    (doseq [n namespaces]
                      (require n))
                    (->> namespaces
                         (map ns-interns)
                         (mapcat vals)
                         (remove (fn [var]
                                   (some (fn [selector]
                                           (let [meta-info (merge (-> var meta :ns meta)
                                                                  (assoc (meta var) :leiningen.test/var var))]
                                             (selector meta-info)))
                                         selectors)))))
        copy #(doseq [v vars]
                (copy-meta v %1 %2))]
    (copy :test :leiningen/skipped-test)
    (try (test-func)
         (finally
           (copy :leiningen/skipped-test :test)))))

(defn covered []
  (mapv (fn [{:keys [^AtomicInteger hits] :as form}]
          (merge form {:hits (.get hits) :covered (pos? (.get hits))}))
        @*covered*))

(defn cover
  "Mark the given file and line in as having been covered."
  [idx]
  ;; Note well that this function is written to have minimal overhead. Make sure
  ;; there are no reflection warnings and especially beware introducing any
  ;; unnecessary coordination here – it can greatly affect performance when
  ;; instrumenting tests that use multithreading.
  (.getAndIncrement ^AtomicInteger (get (nth (.deref ^IDeref *covered*) idx) :hits)))

(defmacro capture
  "Eval the given form and record that the given line on the given
  files was run."
  [idx form]
  (with-meta
    `(do
       (cover ~idx)
       ~form)
    (meta form)))

(defn parse-form
  [form line-hint]
  (debug/tprnl "Parsing form" form "at line" (:line (meta form)) "hint" line-hint)
  (let [lib       *instrumented-ns*
        file      (src/resource-path lib)
        line      (or (:line (meta form)) line-hint)
        form-info {:form      (or (:original (meta form))
                                  form)
                   :full-form form
                   :tracked   true
                   :line      line
                   :lib       lib
                   :file      file
                   :hits      (AtomicInteger. 0)}]
    (binding [*print-meta* true]
      (debug/tprn "Parsed form" form))
    form-info))

(defn track-coverage [line-hint form]
  (debug/tprnl "Track coverage called with" form)
  (let [form' (parse-form form line-hint)
        idx   (dec (count (swap! *covered* conj form')))]
    `(capture ~idx ~form)))

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
                          (and (empty? ns-paths) (empty? regex-patterns))
                          '()

                          (empty? ns-paths)
                          (distinct (ns-find/find-namespaces (filter #(.isDirectory ^java.io.File %)
                                                                     (cp/system-classpath))))

                          :else
                          (distinct (ns-find/find-namespaces (map io/file ns-paths)))))]
    (debug/tprn "found:" {:namespaces     namespaces
                          :ns-paths       ns-paths
                          :regex-patterns regex-patterns})
    (if (seq regex-patterns)
      (filter (fn [ns] (some #(re-matches % ns) regex-patterns)) namespaces)
      namespaces)))

(defn load-namespaces [{:keys [extra-test-ns ns-regex test-ns-regex ns-exclude-regex src-ns-path test-ns-path]} add-nses]
  (let [;; calculate the set of namespaces we're planning on instrumenting
        include     (-> src-ns-path
                        (find-nses ns-regex)
                        (remove-nses ns-exclude-regex)
                        (concat add-nses))
        ;; Find all namespaces in the classpath (and any additional ones included) and then sort them in order so
        ;; dependencies get loaded first.
        all-nses    (dep/in-dependency-order (distinct (map symbol (concat include (find-nses src-ns-path [#".*"])))))
        instrument? (set (map symbol include))
        ;; Now get a sequence of namespaces to instrument in the dependency order. Do this by taking the entire sorted
        ;; sequence of `all-nses` and filtering out ones we don't want to instrument
        namespaces  (filter instrument? all-nses)
        test-nses   (concat extra-test-ns (find-nses test-ns-path test-ns-regex))]
    (when (empty? namespaces)
      (throw (RuntimeException.
              (str "No namespaces selected for instrumentation using " {:ns-regex         ns-regex
                                                                        :ns-exclude-regex ns-exclude-regex}))))
    (println "Loading namespaces: " (apply list namespaces))
    (println "Test namespaces: " test-nses)
    {:test-nses test-nses, :ordered-nses namespaces}))

(defn instrument-namespaces [{:keys [exclude-call nop?]} ordered-nses]
  (when (empty? ordered-nses)
    (throw (RuntimeException. "No namespaces selected for instrumentation.")))
  (let [start-time-ms (System/currentTimeMillis)]
    (doseq [namespace ordered-nses]
      (binding [*instrumented-ns*    namespace
                inst/*exclude-calls* (when (seq exclude-call)
                                       (set exclude-call))]
        (if nop?
          (inst/instrument #'inst/nop namespace)
          (inst/instrument #'track-coverage namespace)))
      (println "Instrumented" namespace)
      ;; mark the ns as loaded
      (mark-loaded namespace))
    (printf "Instrumented %d namespaces in %.1f seconds.\n"
            (count ordered-nses)
            (/ (- (System/currentTimeMillis) start-time-ms)
               1000.0))))

(defn- resolve-var [sym]
  (let [ns (namespace (symbol sym))
        ns (when ns (symbol ns))]
    (when ns
      (require ns))
    (ns-resolve (or ns *ns*)
                (symbol (name sym)))))

(defn- require-ns
  "Require and return namespace by given string."
  [ns-str]
  (let [ns-sym (symbol ns-str)]
    (require ns-sym)
    (find-ns ns-sym)))

(defmulti runner-fn :runner)

(defmethod runner-fn :midje [_]
  (if-let [f (resolve-var 'midje.repl/load-facts)]
    (fn [nses]
      {:errors (:failures (apply f nses))})
    (throw (RuntimeException. "Failed to load Midje."))))

(defmethod runner-fn :clojure.test [{:keys [junit? output] :as _opts}]
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

(defmethod runner-fn :eftest
  [{:keys [runner-opts] :as opts}]
  (fn [nses]
    (let [find-tests-fn (resolve-var 'eftest.runner/find-tests)
          run-tests-fn (resolve-var 'eftest.runner/run-tests)
          eftest-opts (if (and (seq runner-opts)
                               (not (map? runner-opts)))
                        (into {} runner-opts)
                        runner-opts)
          extra-test-ns (or (seq (:extra-test-ns opts)) [])
          test-namespaces (->> (find-nses (:test-ns-path opts) (:test-ns-regex opts))
                               (concat extra-test-ns)
                               (set)
                               (map require-ns)
                               (seq))
          test-vars (find-tests-fn test-namespaces)
          results (run-tests-fn test-vars eftest-opts)]
      (apply require (map symbol nses))
      {:errors (reduce + ((juxt :error :fail) results))})))

(defmethod runner-fn :default [_]
  (throw (IllegalArgumentException.
          "Runner not found. Built-in runners are `clojure.test`, `midje` and `eftest`.")))

(defn run-tests [{:keys [runner test-selectors selector junit?], :as opts} test-nses]
  ;; load runner multimethod definition from other dependencies
  (when-not (#{:clojure.test :midje :eftest} runner)
    (try (require (symbol (format "%s.cloverage" (name runner))))
         (catch FileNotFoundException _)))
  (let [test-result (when (seq test-nses)
                      (if (and junit? (not= runner :clojure.test))
                        (throw (RuntimeException.
                                "Junit output only supported for clojure.test at present"))
                        (let [test-ns-symbols (map symbol test-nses)]
                          (form-for-suppressing-unselected-tests test-ns-symbols
                                                                 (vals (select-keys test-selectors selector))
                                                                 #((runner-fn opts) test-ns-symbols)))))
        forms       (rep/gather-stats (covered))
        ;; sum up errors as in lein test
        num-errors  (when test-result
                      (:errors test-result))]
    (println "Ran tests.")
    {:test-result test-result, :forms forms, :num-errors num-errors}))

(defn launch-custom-report
  [report-sym arg-map]
  (when-let [f (resolve-var report-sym)]
    (debug/tprnl "Custom Report: " report-sym)
    (f arg-map)))

(defn report-results
  [{:keys [text? html? raw? emma-xml? lcov? codecov? coveralls? summary? colorize? low-watermark high-watermark
           custom-report ^String output], :as opts}
   project-opts
   forms]
  (when output
    (.mkdirs (io/file output))
    (when text? (text/report output forms))
    (when html? (html/report output forms))
    (when emma-xml? (emma-xml/report output forms))
    (when lcov? (lcov/report output forms))
    (when raw? (raw/report output forms @*covered*))
    (when codecov? (codecov/report output forms))
    (when coveralls? (coveralls/report output forms))
    (when summary? (console/summary forms low-watermark high-watermark colorize?)))
  (when custom-report (launch-custom-report custom-report {:project project-opts
                                                           :args    opts
                                                           :output  output
                                                           :forms   forms})))

(defn- coverage-under? [forms failure-threshold line-failure-threshold form-failure-threshold]
  {:pre [failure-threshold line-failure-threshold form-failure-threshold]}
  (let [{:keys [percent-lines-covered percent-forms-covered]} (rep/total-stats forms)]
    (if (pos? failure-threshold)
      (let [pct-covered (min percent-lines-covered percent-forms-covered)
            failed?     (< pct-covered failure-threshold)]
        (when failed?
          (println "Failing build as coverage is below threshold of" failure-threshold "%"))
        failed?)
      (when (or (pos? line-failure-threshold) (pos? form-failure-threshold))
        (let [line-failed? (< percent-lines-covered line-failure-threshold)
              form-failed? (< percent-forms-covered form-failure-threshold)]
          (when line-failed?
            (println "Failing build as line coverage is below threshold of" line-failure-threshold "%"))
          (when form-failed?
            (println "Failing build as form coverage is below threshold of" form-failure-threshold "%"))
          (or line-failed? form-failed?))))))

(defn run-main
  [[{:keys [debug? fail-threshold line-fail-threshold form-fail-threshold help?], :as opts} add-nses help] project-opts]
  (binding [*ns*          (find-ns 'cloverage.coverage)
            debug/*debug* debug?]
    (if help?
      (println help)
      (let [{:keys [test-nses ordered-nses]} (load-namespaces opts add-nses)]
        (instrument-namespaces opts ordered-nses)
        (let [{:keys [test-result forms num-errors]} (run-tests opts test-nses)
              exit-code                              (cond
                                                       (not test-result)                      -1
                                                       (> num-errors 128)                     -2
                                                       (coverage-under? forms
                                                                        fail-threshold
                                                                        line-fail-threshold
                                                                        form-fail-threshold)  -3
                                                       :else                                  num-errors)]
          (report-results opts project-opts forms)
          (if *exit-after-test*
            (do (shutdown-agents)
                (System/exit exit-code))
            exit-code))))))

(defn run-project [project-opts & args]
  (try
    (-> args
        (args/parse-args project-opts)
        (run-main project-opts))
    (catch Exception e
      (.printStackTrace e)
      (throw e))))

(defn -main
  "Produce test coverage report for some namespaces"
  [& args]
  (let [project-opts {}]
    (-> args
        (args/parse-args project-opts)
        (run-main project-opts))))
