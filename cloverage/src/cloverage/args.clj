(ns cloverage.args
  (:refer-clojure :exclude [boolean?])
  (:require [clojure.tools.cli :as cli]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import (java.util.regex Pattern)
           (clojure.lang ExceptionInfo)))

(defn- boolean? [x]
  (instance? Boolean x))

(defn- regexes-or-strings?
  [coll]
  (every? #(or (string? %)
               (instance? Pattern %)) coll))

(defn- symbols?
  [coll]
  (every? symbol? coll))

(def valid
  {:text?                 boolean?
   :html?                 boolean?
   :raw?                  boolean?
   :emma-xml?             boolean?
   :junit?                boolean?
   :lcov?                 boolean?
   :codecov?              boolean?
   :coveralls?            boolean?
   :summary?              boolean?
   :colorize?             boolean?
   :fail-threshold        integer?
   :line-fail-threshold   integer?
   :form-fail-threshold   integer?
   :low-watermark         integer?
   :high-watermark        integer?
   :debug?                boolean?
   :nop?                  boolean?
   :extra-test-ns         regexes-or-strings?
   :help?                 boolean?
   :ns-regex              regexes-or-strings?
   :test-ns-regex         regexes-or-strings?
   :ns-exclude-regex      regexes-or-strings?
   :exclude-call          symbols?
   :src-ns-path           regexes-or-strings?
   :runner                keyword?
   :runner-opts           map?
   :test-ns-path          regexes-or-strings?
   :custom-report         symbol?})

(defn- fn-sym [^Object f]
  (let [[_ f-ns f-n] (re-matches #"(.*)\$(.*?)(__[0-9]+)?" (.. f getClass getName))]
    ;; check for anonymous function
    (when (not= "fn" f-n)
      (symbol (clojure.lang.Compiler/demunge f-ns) (clojure.lang.Compiler/demunge f-n)))))

(defn validate [[k v :as pair]]
  (let [f (get valid k (constantly true))]
    (if (f v)
      pair
      (let [message (str "Invalid value: " k ", " v " (should satisfy: " (fn-sym f) ")")]
        (println message)
        (ex-info message {:k k :v v :validation-fn (fn-sym f)})))))

(defn validate! [project-settings]
  (when-let [validation-errors (->> project-settings
                                    (mapv validate) ;; mapv for running all validations. Printing multiple results is friendlier than just one
                                    (filterv (partial instance? ExceptionInfo))
                                    (not-empty))]
    (throw (ex-info "Invalid project settings"
                    {:invalid-pairs (mapv ex-data validation-errors)}))))

(defn- collecting-args-parser []
  (let [col (atom [])]
    (fn [val]
      (swap! col conj val))))

(defn- parse-sym-str [s]
  (->> (str/split s #"/")
       (take 2)
       (apply symbol)))

(defn- parse-kw-str [s]
  (let [s (name s)
        s (if (and s (.startsWith s ":")) (subs s 1) s)]
    (keyword s)))

(def boolean-flags
  (letfn [(add-? [k]
            [k (keyword (str (name k) \?))])]
    (->> [:text :html :raw :emma-xml :junit :lcov :codecov :coveralls :summary :colorize :debug :nop :help]
         (map add-?)
         (into {}))))

(defn- overwrite
  "For each key-value pair in project settings, overwrite the value in opts"
  [opts project-settings]
  (reduce (fn [o [k v]]
            (if (coll? v)
              (update o k concat v)
              (assoc o k v)))
          opts
          project-settings))

(defn- fix-opts
  "Clean the parsed command-line options.
  Rename boolean flags then merge in any project settings."
  [[opts add-nses help] project-settings]
  (let [->regexes (partial map re-pattern)
        ->symbols (partial map symbol)
        opts      (-> opts
                      (update :ns-regex ->regexes)
                      (update :test-ns-regex ->regexes)
                      (update :ns-exclude-regex ->regexes)
                      (update :exclude-call ->symbols)
                      (set/rename-keys boolean-flags)
                      (overwrite project-settings)
                      (update :test-selectors #(into {} %)))]
    [opts add-nses help]))

;; This is a defn for avoiding state across test runs
(defn arguments []
  [["-o" "--output" "Output directory." :default "target/coverage"]
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
   ["--[no-]colorize"
    "Adds ANSI color to the summary" :default true]
   ["--fail-threshold"
    "Sets the percentage threshold for both line and form coverage at which cloverage will abort the build. Default: 0%"
    :default 0
    :parse-fn #(Integer/parseInt %)]
   ["--line-fail-threshold"
    "Sets the percentage threshold for line coverage at which cloverage will abort the build.
    Ignored if --fail-threshold is non-zero. Default: 0%"
    :default 0
    :parse-fn #(Integer/parseInt %)]
    ["--form-fail-threshold"
    "Sets the percentage threshold for form coverage at which cloverage will abort the build.
    Ignored if --fail-threshold is non-zero. Default: 0%"
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
    "Specify which test runner to use. Built-in runners are `clojure.test`, `midje` and `eftest`."
    :default :clojure.test
    :parse-fn parse-kw-str]
   ["--[no-]nop" "Instrument with noops." :default false]
   ["-n" "--ns-regex"
    "Regex for instrumented namespaces (can be repeated)."
    :default []
    :parse-fn (collecting-args-parser)]
   ["-e" "--ns-exclude-regex"
    "Regex for namespaces not to be instrumented (can be repeated)."
    :default []
    :parse-fn (collecting-args-parser)]
   ["--exclude-call"
    "Name of fn/macro whose call sites are not to be instrumented (can be repeated)."
    :default []
    :parse-fn (collecting-args-parser)]
   ["-t" "--test-ns-regex"
    "Regex for test namespaces (can be repeated)."
    :default []
    :parse-fn (collecting-args-parser)]
   ["-p" "--src-ns-path"
    "Path (string) to directory containing source code namespaces (can be repeated)."
    :default []
    :parse-fn (collecting-args-parser)]
   ["-s" "--test-ns-path"
    "Path (string) to directory containing test namespaces (can be repeated)."
    :default []
    :parse-fn (collecting-args-parser)]
   ["-x" "--extra-test-ns"
    "Additional test namespace (string) to add (can be repeated)."
    :default []
    :parse-fn (collecting-args-parser)]
   ["--selector"
    "Apply test selector (can be repeated)"
    :default []
    :parse-fn (comp (collecting-args-parser) parse-kw-str)]
   ["-c" "--custom-report"
    "Load and run a custom report writer. Should be a namespaced symbol. The function is passed
    project-options args-map output-directory forms"
    :parse-fn parse-sym-str]
   ["-h" "--help" "Show help." :default false :flag true]])

(defn parse-args [args project-settings]
  #_{:clj-kondo/ignore [:deprecated-var]}
  (fix-opts (apply cli/cli args (arguments))
            (doto project-settings validate!)))
