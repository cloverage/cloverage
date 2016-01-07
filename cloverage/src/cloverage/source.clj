(ns cloverage.source
  (:import java.lang.IllegalArgumentException)
  (:require [clojure.tools.reader.reader-types :as rt]
            [clojure.tools.reader :as r])
  (:use    [cloverage.debug]))

(defn resource-reader [resource]
  (when-let [resource (.getResourceAsStream
                        (clojure.lang.RT/baseLoader) resource)]
            resource))

(defn concat-path
  [lib extension]
  (str (.. (name lib)
           (replace \- \_)
           (replace \. \/))
       extension))

(defn resource-path*
  "Given a symbol representing a lib, return a classpath-relative path (clj or cljc). Borrowed from core.clj."
  [lib]
  (let [clj-path (concat-path lib ".clj")
        clj-file (resource-reader clj-path)]
       (if clj-file
         clj-path
         (let [cljc-path (concat-path lib ".cljc")
               cljc-file (resource-reader cljc-path)]
              (if cljc-file
                cljc-path
            (throw (IllegalArgumentException.
                     (str "Cannot find resource " lib " (.clj or .cljc)"))))))))

(def resource-path (memoize resource-path*))


(defn form-reader [ns-symbol]
  (rt/indexing-push-back-reader
    (rt/input-stream-push-back-reader
      (resource-reader (resource-path ns-symbol)))))

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
                                                         :features #{:clj}
                                                         :read-cond :allow} src)))))))
