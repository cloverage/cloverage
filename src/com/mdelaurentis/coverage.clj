(ns com.mdelaurentis.coverage
  (:import [clojure.lang LineNumberingPushbackReader IObj]
           [java.io File InputStreamReader])
  (:use [clojure.contrib.duck-streams :only [reader with-out-writer copy]]
        [clojure.contrib.command-line :only [with-command-line]]
        [clojure.contrib.except])
  (:gen-class))

(def *covered*)

(defmacro with-coverage [libs & body]
  `(binding [*covered* (ref [])]
     (println "Capturing code coverage for" ~libs)
     (doseq [lib# ~libs]
       (instrument lib#))
     ~@body
     (gather-stats @*covered*)))

(defn covered? [coverage file line]
  ((coverage file {}) line))

(defn cover [idx]
  "Mark the given file and line in as having been covered."
  (dosync (alter *covered* assoc-in [idx :covered] true)))

(defmacro capture 
  "Eval the given form and record that the given line on the given
  files was run."
  [idx form]
  (let [text (with-out-str (prn form))]
    `(do 
       (cover ~idx)
       ~form)))

(defn form-type 
  "Classifies the given form"
  [form]
  (cond 
   (= '. form)   :stop
   (= 'do form) :stop
   (= 'if form)  :stop
   (= 'var form) :stop   
   (= 'quote form) :stop
   (= 'try form) :stop
   (= 'finally form) :stop   
   (vector? form) :vector
   (string? form) :primitive
   (number? form) :primitive
   (symbol? form) :primitive
   (keyword? form) :primitive
   (= true form) :primitive
   (= false form) :primitive
   (var? form) :primitive
   (nil? form) :primitive
   (map?    form) :map
   
   (or (list? form) (seq? form))
   (let [x (first form)]
     (cond
      (= 'var x) :stop
      (= 'finally x) :stop
      (= '. x) :stop
      (= 'quote x) :stop
      (= 'fn x) :fn
      (= 'fn* x) :fn
      (= 'let* x) :let
      (= 'def x)  :def
      :else       :list))))

(defn add-form 
  "Adds a structure representing the given form to the *covered* vector."
  [form]
  (dosync 
   (alter *covered* conj {:form form
                          :line (:line (meta form))
                          :file (:file (meta form))})
   (dec (count @*covered*))))

(defmulti wrap form-type)

(defn expand-and-wrap [form]
  (cond
   (and (or (seq? form) (list? form))
        (= 'ns (first form)))
   form
   
   (instance? IObj form)
   (vary-meta (wrap (macroexpand form))
              assoc :original form)

   :else
   (wrap (macroexpand form))))

(defmethod wrap :stop [form]
  form)

(defmethod wrap :primitive [form]
  `(capture ~(add-form form) ~form))

(defmethod wrap :vector [form]
  `[~@(map wrap form)])

(defn wrap-overload [[args & body]]
    `([~@args]
        ~@(map expand-and-wrap body)))

(defn wrap-overloads [form]
  (if (vector? (first form))
    (wrap-overload form)
    (map wrap-overload form)))

(defmethod wrap :fn [form]
  (let [fn-sym (first form)]
    (if (symbol? (second form))
      `(capture ~(add-form form)
                (~fn-sym ~(second form)
                         ~@(wrap-overloads (rest (rest form)))))
      `(capture ~(add-form form)
                (~fn-sym
                 ~@(wrap-overloads (rest form)))))))

(defmethod wrap :let [[let-sym bindings & body :as form]]
  `(capture ~(add-form form)
            (~let-sym
             [~@(mapcat (fn [[name val]] `(~name ~(expand-and-wrap val)))
                        (partition 2 bindings))]
             ~@(map expand-and-wrap body))))

(defmethod wrap :def [form]
  (let [def-sym (first form)
        name    (second form)]
    (if (= 3 (count form))
      (let [val (nth form 2)]
        `(capture ~(add-form form) (~def-sym ~name ~(expand-and-wrap val))))
      `(capture ~(add-form form) (~def-sym ~name)))))

(defmethod wrap :list [form]
  (let [wrapped (map expand-and-wrap form)]
    `(capture ~(add-form form) ~wrapped)))

(defmethod wrap :map [form]
  (zipmap (map expand-and-wrap (keys form))
          (map expand-and-wrap (vals form))))

(defmethod wrap :default [form]
  form)

(defn resource-path 
  "Given a symbol representing a lib, return a classpath-relative path.  Borrowed from core.clj."
  [lib]
  (str (.. (name lib)
           (replace \- \_)
           (replace \. \/))
       ".clj"))

(defn resource-reader [resource]
  (InputStreamReader.
   (.getResourceAsStream (clojure.lang.RT/baseLoader) resource)))

(defn instrument
  "Reads all forms from the file referenced by the given lib name, and
  returns a seq of all the forms, decorated with a function that when
  called will record the line and file of the code that was executed."
  [lib]
  (println "Instrumenting" lib)
  (when-not (symbol? lib)
    (throwf "instrument needs a symbol"))
  (let [file (resource-path lib)]
    (println "File is " file)
    (with-open [in (LineNumberingPushbackReader. (resource-reader file))]
      (loop [forms nil]
        (if-let [form (read in false nil true)]
          (let [wrapped (try (expand-and-wrap form)
                             (catch Throwable t
                               (throwf t "Couldn't wrap form %s at line %s"
                                       form (:line form))))]
            (recur (conj forms wrapped)))
          (reverse forms))))))


(defn gather-stats [cov]
  (for [[file fcov] cov]
    (with-open [in (LineNumberingPushbackReader. (resource-reader file))]
      (loop [forms nil]
        (if-let [text (.readLine in)]
          (let [line (dec (.getLineNumber in))
                info   {:line line
                        :text text
                        :blank? (.isEmpty (.trim text))
                        :covered? (fcov line)}]
            (recur (conj forms info)))
          {:file file
           :content (apply vector nil (reverse forms))})))))

(defn report [out-dir cov]
  (doseq [{rel-file :file, content :content} cov]
    (let [file (File. out-dir rel-file)]
      (.mkdirs (.getParentFile file))
      (with-out-writer file
        (doseq [info content]
          (let [prefix (cond (:blank? info)   " "
                             (:covered? info) "+"
                             :else            "-")]
            (println prefix (:text info))))))))

(defn replace-spaces [s]
  (.replace s " " "&nbsp;"))

(defn html-report [out-dir cov]
  (copy (resource-reader "coverage.css") (File. out-dir "coverage.css"))
  (doseq [{rel-file :file, content :content} cov]
    (let [file (File. out-dir (str rel-file ".html"))]
      (.mkdirs (.getParentFile file))
      (with-out-writer file
        (println "<html>")
        (println " <head>")
        (println "  <link rel=\"stylesheet\" href=\"../../coverage.css\"/>")
        (println "  <title>" rel-file "</title>")
        (println " </head>")
        (println " <body>")
        (doseq [info content]
          (let [cls (cond (:covered? info) "covered" 
                          (:blank?   info) "blank"
                          :else            "not-covered")]
            (printf "<span class=\"%s\">%s</span><br/>%n" cls (replace-spaces (:text info "")))))
        (println " </body>")
        (println "</html>")))))

(defn -main [& args]
  (with-command-line args
    "Produce test coverage report for some namespaces"
    [namespaces]
    (binding [*covered* (ref [])]
      (doseq [ns namespaces
              form (instrument (symbol ns))]
        (try
         (eval form)
         (catch Exception e
           (throw (Exception. 
                   (str "Couldn't eval form " (:original (meta form)))
                   e))))))))

