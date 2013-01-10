(ns cloverage.source
  (:import java.io.InputStreamReader
           java.lang.IllegalArgumentException
           clojure.lang.LineNumberingPushbackReader)
  (:use    [cloverage.debug]))

(defn resource-path
  "Given a symbol representing a lib, return a classpath-relative path.  Borrowed from core.clj."
  [lib]
  (str (.. (name lib)
           (replace \- \_)
           (replace \. \/))
       ".clj"))

(defn resource-reader [resource]
  (if-let [resource (.getResourceAsStream
                      (clojure.lang.RT/baseLoader)
                      resource)]
    (InputStreamReader. resource)
    (throw (IllegalArgumentException. (str "Cannot find resource " resource)))))

(defn form-reader [ns-symbol]
  (LineNumberingPushbackReader. (resource-reader (resource-path ns-symbol))))

(defn forms [ns-symbol]
  (with-open [src (form-reader ns-symbol)]
    (loop [forms nil]
      (if-let [form (read src false nil true)]
        (recur (conj forms form))
        (reverse forms)))))

(defn ns-form [ns-symbol]
  (with-open [src (form-reader ns-symbol)]
    (first (drop-while #(not= 'ns (first %))
                       (take-while (comp not nil?)
                                   (repeatedly #(read src false nil true)))))))
