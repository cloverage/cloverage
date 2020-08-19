(ns cloverage.source
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.namespace.file :as ns.file]
            [clojure.tools.reader :as r]
            [clojure.tools.reader.reader-types :as rt])
  (:import clojure.lang.RT
           [java.io File InputStreamReader PushbackReader]))

(defn- get-loader ^ClassLoader []
  (RT/baseLoader))

(defn resource-path
  "Given a symbol naming a namespace, return a classpath-relative path to the file defining that namespace.

    (resource-path 'cloverage.source)
    ;; -> \"cloverage/source.clj\""
  [ns-symbol]
  (let [base (.. (name ns-symbol)
                 (replace \- \_)
                 (replace \. \/))]
    (some (fn [extension]
            (let [filename (str base extension)]
              ;; io/resource will return nil if the file doesn't exist
              (when (io/resource filename (get-loader))
                filename)))
          ns.file/clojure-extensions)))

(defn ns-form
  "Return the `ns` declaration form from the file associated with the namespace named by `ns-symbol`.

    (ns-form 'cloverage.source)
    ;; ->
    (ns cloverage.source
      (:require [clojure.java.io :as io]
                [clojure.string :as str]
                [clojure.tools.namespace.file :as ns.file])
      (:import clojure.lang.RT
               java.io.File))"
  [ns-symbol]
  (let [resource (or (some->> (resource-path ns-symbol)
                              (.getResource (get-loader)))
                     (io/file (resource-path ns-symbol))
                     (throw (ex-info (format "Cannot read ns declaration for namespace %s: cannot find file" ns-symbol)
                                     {:ns ns-symbol})))]
    (or (ns.file/read-file-ns-decl resource)
        (throw (ex-info (format "Cannot read ns declaration for namespace %s: ns declaration not found in file" ns-symbol)
                        {:ns ns-symbol})))))

(defn source-file-path
  "Given a classpath-relative path, return a relative source file path."
  [resource]
  (let [cwd           (-> (File. "") .getAbsolutePath (str "/"))
        resource-path (-> (.getResource (get-loader) resource) .toURI .getPath)]
    (str/replace resource-path cwd "")))

(defn resource-reader ^java.io.Closeable [resource]
  (if-let [resource (and resource
                         (.getResourceAsStream
                          (get-loader)
                          resource))]
    (InputStreamReader. resource) ; We assume the default charset is set correctly
    (throw (IllegalArgumentException. (str "Cannot find resource " resource)))))

(defn form-reader ^java.io.Closeable [ns-symbol]
  (if-let [res-path (resource-path ns-symbol)]
    (rt/indexing-push-back-reader
     (PushbackReader.
      (resource-reader res-path)))
    (throw (ex-info (format "Resource path not found for namespace: %s" (name ns-symbol))
                    {:ns-symbol ns-symbol}))))

(defn forms
  "Return a lazy sequence of all forms in a source file using `source-reader` to read them."
  [source-reader]
  ;; `read-form` will return `nil` at the end of the file so keep reading forms until we run out
  (letfn [(read-form []
            (binding [*read-eval* false]
              (r/read {:eof       ::eof
                       :features  #{:clj}
                       :read-cond :allow}
                      source-reader)))]
    (take-while (partial not= ::eof) (repeatedly read-form))))
