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
    (println (cheshire.core/generate-string data {:pretty true}))
    (clojure.data.json/pprint data :escape-slash escape-slash)))
