(ns cloverage.report.cobertura-test
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cloverage.args :as args]
            [cloverage.report.cobertura :as cobertura]
            [cloverage.source :as source]))

(defn- child [node tag]
  (first (filter #(= tag (:tag %)) (:content node))))

(defn- parsed-report [forms]
  (with-redefs [source/source-file-path identity]
    (xml/parse-str (with-out-str (cobertura/write-cobertura-report forms)))))

(defn- tracked [file lib line hits]
  {:file file :lib lib :line line :text "code" :tracked true :covered (pos? hits) :hits hits})

(deftest reports-real-lines-and-weighted-totals
  (let [forms [(tracked "alpha/core.clj" 'alpha.core 4 7)
               (tracked "alpha/core.clj" 'alpha.core 4 3)
               (tracked "alpha/core.clj" 'alpha.core 9 0)
               (tracked "alpha/util.clj" 'alpha.util 2 1)
               (tracked "beta/core.clj" 'beta.core 3 0)
               (tracked "beta/core.clj" 'beta.core 3 2)
               (tracked "beta/core.clj" 'beta.core 8 0)
               {:file "alpha/core.clj" :lib 'alpha.core :line 1 :text ""}
               {:file "alpha/core.clj" :lib 'alpha.core :line 2 :text "untracked"}]
        report (parsed-report forms)
        packages (:content (child report :packages))
        classes (:content (child (first packages) :classes))
        lines (:content (child (first classes) :lines))]
    (is (= "5" (get-in report [:attrs :lines-valid])))
    (is (= "3" (get-in report [:attrs :lines-covered])))
    (is (= 0.6 (Double/parseDouble (get-in report [:attrs :line-rate]))))
    (is (= ["alpha" "beta"] (map #(get-in % [:attrs :name]) packages)))
    (is (= (/ 2.0 3.0) (Double/parseDouble (get-in (first packages) [:attrs :line-rate]))))
    (is (= ["alpha/core.clj" "alpha/util.clj"] (map #(get-in % [:attrs :filename]) classes)))
    (is (= "alpha.core" (get-in (first classes) [:attrs :name])))
    (is (= 0.5 (Double/parseDouble (get-in (first classes) [:attrs :line-rate]))))
    (is (= [{:number "4" :hits "7" :branch "false"}
            {:number "9" :hits "0" :branch "false"}]
           (map :attrs lines)))
    (is (= :methods (:tag (child (first classes) :methods))))
    (is (= "0" (get-in report [:attrs :branches-valid])))
    (is (= "0" (get-in report [:attrs :branches-covered])))
    (is (= "0.0" (get-in report [:attrs :branch-rate])))
    (is (pos? (Long/parseLong (get-in report [:attrs :timestamp]))))))

(deftest empty-reports-and-root-namespaces
  (let [report (parsed-report [])]
    (is (= "0" (get-in report [:attrs :lines-valid])))
    (is (= "0" (get-in report [:attrs :lines-covered])))
    (is (= "0.0" (get-in report [:attrs :line-rate])))
    (is (empty? (:content (child report :packages)))))
  (let [report (parsed-report [(tracked "core.clj" 'core 1 1)])
        package (first (:content (child report :packages)))
        class (first (:content (child package :classes)))]
    (is (= "" (get-in package [:attrs :name])))
    (is (= "core.clj" (get-in class [:attrs :filename])))))

(deftest cobertura-command-line-and-project-options
  (is (true? (:cobertura? (first (args/parse-args ["--cobertura"] {})))))
  (is (false? (:cobertura? (first (args/parse-args ["--no-cobertura"] {})))))
  (is (true? (:cobertura? (first (args/parse-args [] {:cobertura? true})))))
  (is (false? (:cobertura? (first (args/parse-args [] {}))))))

(deftest escapes-filenames-and-ignores-forms-without-a-source-file
  (let [report (parsed-report [(tracked "a&b/core.clj" 'a&b.core 1 1)
                               {:file nil :line 2 :tracked true :covered false :hits 0}])
        package (first (:content (child report :packages)))
        class (first (:content (child package :classes)))]
    (is (= "1" (get-in report [:attrs :lines-valid])))
    (is (= "a&b/core.clj" (get-in class [:attrs :filename])))))

(defmacro ^:private if-jvm [& body]
  (when-not (System/getProperty "babashka.version")
    `(do ~@body)))

(deftest conforms-to-the-cobertura-dtd
  ;; Validate against the vendored upstream DTD without accessing the network.
  ;; Babashka's XML writer is checked by the data tests above; its runtime does
  ;; not expose the JVM's validating XML parser.
  (if-jvm
   (doseq [forms [[] [(tracked "core.clj" 'core 1 0)
                      (tracked "foo/core.clj" 'foo.core 2 3)]]]
     (let [factory (doto (javax.xml.parsers.DocumentBuilderFactory/newInstance)
                     (.setValidating true))
           builder (.newDocumentBuilder factory)
           xml-text (with-redefs [source/source-file-path identity]
                      (with-out-str (cobertura/write-cobertura-report forms)))
           xml-text (str/replace xml-text "<coverage "
                                 "<!DOCTYPE coverage SYSTEM 'coverage-04.dtd'><coverage ")]
       (.setEntityResolver builder
                           (reify org.xml.sax.EntityResolver
                             (resolveEntity [_ _public-id _system-id]
                               (org.xml.sax.InputSource.
                                (io/reader (io/resource "cloverage/schema/coverage-04.dtd"))))))
       (.setErrorHandler builder
                         (reify org.xml.sax.ErrorHandler
                           (warning [_ e] (throw e))
                           (error [_ e] (throw e))
                           (fatalError [_ e] (throw e))))
       (is (= "coverage" (-> (.parse builder (org.xml.sax.InputSource. (java.io.StringReader. xml-text)))
                             .getDocumentElement .getTagName)))))))
