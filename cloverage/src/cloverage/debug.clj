(ns cloverage.debug
  (:require [clojure.java.io :refer [writer]]
            clojure.pprint))

(def ^:dynamic *debug*
  "Whether to enable Cloverage debugging output."
  false)

(defn tprn
  "Like `clojure.pprint/pprint`, but only prints when Cloverage debugging is enabled."
  [& args]
  (when *debug*
    (run! clojure.pprint/pprint args)
    (newline)))

(defn tprnl
  "Like `println`, but only prints when Cloverage debugging is enabled."
  [& args]
  (when *debug*
    (apply println args)))

(defn tprf
  "Like `printf`, but only prints when Cloverage debugging is enabled."
  [& args]
  (when *debug*
    (apply printf args)))

(defn dump-instrumented [forms name]
  (when *debug*
    (with-open [ou (writer (str "debug-" name))]
      (binding [*out* ou
                *print-meta* true]
        (run! prn forms)))))
