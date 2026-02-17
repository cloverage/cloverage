(ns cloverage.report.json)

(def ^:private bb? (System/getProperty "babashka.version"))

(defmacro ^:private if-bb [then else]
  (if bb? then else))

(if-bb
  (require '[cheshire.core])
  (require '[clojure.data.json]))

(defn pprint
  "Pretty-print data as JSON to *out*."
  [data & {:keys [escape-slash] :or {escape-slash true}}]
  (if-bb
    #_{:clj-kondo/ignore [:unresolved-namespace]}
    (println (cheshire.core/generate-string data {:pretty true}))
    #_{:clj-kondo/ignore [:unresolved-namespace]}
    (clojure.data.json/pprint data :escape-slash escape-slash)))
