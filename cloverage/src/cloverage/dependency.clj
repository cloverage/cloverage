(ns cloverage.dependency
  (:require [clojure.tools.namespace.dependency :as ns.deps]
            [clojure.tools.namespace.parse :as ns.parse]
            [cloverage.source :as source]))

(defn- dependencies
  "Return a set of namespace symbols that are dependencies of a namespace named by `ns-symbol`.

    (dependencies 'cloverage.dependency)
    ;; -> #{clojure.tools.namespace.dependency clojure.tools.namespace.parse cloverage.source}"
  [ns-symbol]
  (ns.parse/deps-from-ns-decl (source/ns-form ns-symbol)))

(defn- dependencies-graph
  "Return a `clojure.tools.namespace` dependency graph of namespaces named by `ns-symbol`."
  [ns-symbols]
  (reduce
   (fn [graph ns-symbol]
     (reduce
      (fn [graph dep]
        (ns.deps/depend graph ns-symbol dep))
      graph
      (dependencies ns-symbol)))
   (ns.deps/graph)
   ns-symbols))

(defn in-dependency-order
  "Sort a list of namespace symbols so that any namespace occurs after its dependencies."
  [ns-symbols]
  (ns.deps/topo-sort (dependencies-graph ns-symbols)))
