(ns cloverage.dependency
  (:use [cloverage.kahn :only (kahn-sort)]
        [cloverage.source :only (ns-form)]))

;;(do
;; (clojure.core/in-ns 'cloverage.coverage)
;; (clojure.core/with-loading-context
;;  (clojure.core/gen-class
;;   :name
;;   "cloverage.coverage"
;;   :impl-ns
;;   cloverage.coverage
;;   :main
;;   true)
;;  (clojure.core/refer 'clojure.core)
;;  (clojure.core/import
;;   '[clojure.lang LineNumberingPushbackReader IObj]
;;   '[java.io File InputStreamReader]
;;   '[java.lang Runtime])
;;  (clojure.core/use
;;   '[clojure.java.io :only [reader writer copy]]
;;   '[clojure.tools.cli :only [cli]]
;;   '[cloverage instrument debug report])
;;  (clojure.core/require
;;   '[clojure.set :as set]
;;   nil
;;clojure.core> '[clojure.test :as test]
;;   '[clojure.tools.logging :as log]))


;; snipped from clojure.core
;; either 'lib.name, '[lib.name] or '[lib.name :keyword & args]
(defn- libspec?
  "Returns true if x is a libspec"
  [x]
  (or (symbol? x)
      (and (vector? x)
           (or (nil? (second x))
               (keyword? (second x))))))

(defn- spec-dependencies [libspec]
  (cond
    (symbol?  libspec) [libspec]
    (libspec? libspec) [(first (filter (complement keyword?) libspec))]
    (vector?  libspec) (let [[prefix & args] libspec]
                         (map #(symbol (str prefix \. (if (seq? %) (first %) %))) args))))

(defn- ref-dependencies [reference]
  (when (#{:use :require :load} (first reference))
    (mapcat spec-dependencies (rest reference))))

(defn dependency-libs
  "Given a (ns ...) form, return the ns name and a list of namespaces
   it depends on."
  [[ns-sym ns-nam & refs]]
  [ns-nam (set (mapcat ref-dependencies refs))])

(defn dependency-sort
  "Given a list of [ns-name dependencies] pairs, return a topological
   sort of the dependency graph."
  [dep-lists]
  (let [dep-graph (into {} dep-lists)]
    (reverse (filter (set (keys dep-graph)) (kahn-sort dep-graph)))))

(defn in-dependency-order
  "Sort a list of namespace symbols so that any namespace occurs after
   its dependencies."
  [nses]
  (dependency-sort (map #(-> % ns-form dependency-libs) nses)))
