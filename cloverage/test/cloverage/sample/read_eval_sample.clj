(ns cloverage.sample.read-eval-sample)

(def ^:dynamic *dynamic-var*)

(defmacro with-bound-dynamic-var
  [& body]
  `(binding [*dynamic-var* true]
     (do ~@body)))

(defn do-something [arg]
  (with-bound-dynamic-symbol arg))
