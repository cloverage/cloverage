(ns cloverage.sample.read-eval-sample)

(def ^:dynamic dynamic-symbol)
(defmacro with-bound-dynamic-symbol
  [& body]
  `(binding [dynamic-symbol true]
     (do ~@body)))

(defn do-something [arg]
  (with-bound-dynamic-symbol arg))
