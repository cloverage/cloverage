(ns cloverage.source
  (:require [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt]
            [clojure.java.io :as io]))

(defn- get-loader ^ClassLoader []
  (clojure.lang.RT/baseLoader))

(defn- resource-exists? [path]
  (not (nil? (io/resource path (get-loader)))))

(defn resource-path
  "Given a symbol representing a lib, return a classpath-relative path.  Borrowed from core.clj."
  [lib]
  (let [base (.. (name lib)
                 (replace \- \_)
                 (replace \. \/))]
    (->> ["clj" "cljc"]
         (map #(str base "." %))
         (filter resource-exists?)
         first)))

(defn source-file-path
  "Given a classpath-relative path, return a relative source file path."
  [resource]
  (let [cwd           (-> (java.io.File. "") .getAbsolutePath (str "/"))
        resource-path (-> (.getResource (get-loader) resource) .toURI .getPath)]
    (clojure.string/replace resource-path cwd "")))

(defn resource-reader [resource]
  (if-let [resource (and resource
                         (.getResourceAsStream
                          (get-loader)
                          resource))]
    (java.io.InputStreamReader. resource) ; We assume the default charset is set correctly
    (throw (IllegalArgumentException. (str "Cannot find resource " resource)))))

(defn form-reader [ns-symbol]
  (if-let [res-path (resource-path ns-symbol)]
    (rt/indexing-push-back-reader
     (java.io.PushbackReader.
      (resource-reader res-path)))
    (throw (ex-info (format "Resource path not found for namespace: %s" (name ns-symbol))
                    {:ns-symbol ns-symbol}))))

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
                                                         :read-cond :allow}
                                                        src)))))))
