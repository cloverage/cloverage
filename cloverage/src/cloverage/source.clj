(ns cloverage.source
  (:import java.lang.IllegalArgumentException)
  (:require [clojure.tools.reader.reader-types :as rt]
            [clojure.tools.reader :as r])
  (:use    [cloverage.debug]))

(defn resource-path
  "Given a symbol representing a lib, return a classpath-relative path.  Borrowed from core.clj."
  ([lib] (resource-path lib "clj"))
  ([lib extension]
    (str (.. (name lib)
             (replace \- \_)
             (replace \. \/))
         extension)))

(defn resource-reader [resource]
  (when-let [resource (.getResourceAsStream
                        (clojure.lang.RT/baseLoader)
                        resource)]
            resource))

(defn form-reader [ns-symbol]
  (rt/indexing-push-back-reader
    (rt/input-stream-push-back-reader
      (or (resource-reader (resource-path ns-symbol ".clj"))
          (resource-reader (resource-path ns-symbol ".cljc"))
          (throw (IllegalArgumentException. (str "Cannot find resource " ns-symbol " (.clj or .cljc)")))))))

(defn forms [ns-symbol]
  (let [src (form-reader ns-symbol)]
    (loop [forms nil]
      (if-let [form (r/read src false nil)]
        (recur (conj forms form))
        (reverse forms)))))

(defn ns-form [ns-symbol]
  (let [src (form-reader ns-symbol)]
    (first (drop-while #(not= 'ns (first %))
                       (take-while (comp not nil?)
                                   (repeatedly #(r/read {:eof nil
                                                         :read-cond :allow} src)))))))
