(ns cloverage.custom-reporter
  "Testing for a custom reporter")

(defn reporter-fn [{:keys [:args]}]
  (first args))
