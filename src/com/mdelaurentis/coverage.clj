(ns com.mdelaurentis.coverage
  (:import [clojure.lang LineNumberingPushbackReader IObj]
           [java.io File InputStreamReader])
  (:use [clojure.contrib.duck-streams :only [reader with-out-writer copy]]
        [clojure.contrib.command-line :only [with-command-line]]
        [clojure.contrib.except])
  (:require [clojure.set :as set])
  (:gen-class))

(def *covered*)
(def *instrumenting-file*)

(defmacro with-coverage [libs & body]
  `(binding [*covered* (ref [])]
     (println "Capturing code coverage for" ~libs)
     (doseq [lib# ~libs]
       (instrument lib#))
     ~@body
     (gather-stats @*covered*)))

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
  (let [res
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
            :else       :list)))]
    #_(println "Type of" form "is" res)
    res))

(defn add-form 
  "Adds a structure representing the given form to the *covered* vector."
  [form]
  (let [file *instrumenting-file*
        form-info {:form form
                   :line (:line (meta form))
                   :file file}]
  (binding [*print-meta* true]
    #_(prn "Adding" form-info)
    #_(newline))
    (dosync 
     (alter *covered* conj form-info)
     (dec (count @*covered*)))))

(defmulti wrap form-type)

(defn remove-nil-line [m]
  (if (:line m)
    m
    (dissoc m :line)))


(defn expand-and-wrap [form]
  #_(println "expand-and-wrap" form)
  (cond
   (and (or (seq? form) (list? form))
        (= 'ns (first form)))
   form
   
   (or (seq? form) (list? form))
   (do
     (let [wrapped (doall (wrap (macroexpand form)))]
       #_(prn "Form is" form ", line is" (:line (meta form)))
       (if (instance? IObj form)
         (-> wrapped
             (vary-meta assoc :original (with-meta form {}))
             (vary-meta remove-nil-line))
         wrapped)))

   :else
   (wrap form)))

(defmethod wrap :stop [form]
  form)

(defmethod wrap :primitive [form]
  `(capture ~(add-form form) ~form))

(defmethod wrap :vector [form]
  `[~@(doall (map expand-and-wrap form))])

(defn wrap-overload [[args & body]]
  #_(println "Wrapping overload" args body)
  (let [wrapped (doall (map expand-and-wrap body))]
    `([~@args] ~@wrapped)))

(defn wrap-overloads [form]
  #_(println "Wrapping overloads " form)
  (if (vector? (first form))
    (wrap-overload form)
    (try
     (doall (map wrap-overload form))
     (catch Exception e
       #_(println "ERROR: " form)
       (throw (Exception. (apply str "While wrapping" form) e))))))

(defmethod wrap :fn [form]
  #_(println "Wrapping fn " form)
  (let [fn-sym (first form)
        res    (if (symbol? (second form))
                 `(capture ~(add-form form)
                           (~fn-sym ~(second form)
                                    ~@(wrap-overloads (rest (rest form)))))
                 `(capture ~(add-form form)
                           (~fn-sym
                            ~@(wrap-overloads (rest form)))))]
    #_(println "Wrapped is" res)
    res))

(defmethod wrap :let [[let-sym bindings & body :as form]]
  `(capture ~(add-form form)
            (~let-sym
             [~@(doall (mapcat (fn [[name val]] `(~name ~(expand-and-wrap val)))
                               (partition 2 bindings)))]
             ~@(doall (map expand-and-wrap body)))))

(defmethod wrap :def [form]
  (let [def-sym (first form)
        name    (second form)]
    (if (= 3 (count form))
      (let [val (nth form 2)]
        `(capture ~(add-form form) (~def-sym ~name ~(expand-and-wrap val))))
      `(capture ~(add-form form) (~def-sym ~name)))))

(defmethod wrap :list [form]
  #_(println "Wrapping " (class form) form)
  (let [wrapped (doall (map expand-and-wrap form))]
    `(capture ~(add-form form) ~wrapped)))

(defmethod wrap :map [form]
  (doall (zipmap (doall (map expand-and-wrap (keys form)))
                 (doall (map expand-and-wrap (vals form))))))

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
    (binding [*instrumenting-file* file]
      (with-open [in (LineNumberingPushbackReader. (resource-reader file))]
        (loop [forms nil]
          (if-let [form (read in false nil true)]
            (let [wrapped (try (expand-and-wrap form)
                               (catch Throwable t
                                 (throwf t "Couldn't wrap form %s at line %s"
                                         form (:line form))))]
              (recur (conj forms wrapped)))
            (reverse forms)))))))

(defn gather-stats [cov]
  (let [indexed (set/index cov [:file :line])]
    (for [file (filter #(not (= "NO_SOURCE_FILE" %))
                       (distinct (map :file (keys indexed))))]
      (do
        (with-open [in (LineNumberingPushbackReader. (resource-reader file))]
          (loop [forms nil]
            (if-let [text (.readLine in)]
              (let [line (dec (.getLineNumber in))
                    info  {:line line
                           :file file
                           :text text
                           :forms (indexed {:file file :line line})}]
                (recur (conj forms info)))
              {:file file
               :content (apply vector nil (reverse forms))})))))))

(defn report [out-dir cov]
  (doseq [{rel-file :file, content :content} cov]
    (let [file (File. out-dir rel-file)]
      (.mkdirs (.getParentFile file))
      (with-out-writer file
        (doseq [line-info content]
          (let [prefix (cond (empty? (:forms line-info))   " "
                             (some :covered (:forms line-info)) "+"
                             :else            "-")]
            (println prefix (:text line-info))))))))

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
    [[output o "Output directory"]
     namespaces]
    (binding [*covered* (ref [])]
      (doseq [namespace (map symbol namespaces)]
        (doseq [form (instrument namespace)]
          (try
           (eval form)
           (catch Exception e
             (throw (Exception. 
                     (str "Couldn't eval form " 
                          (binding [*print-meta* true]
                            (with-out-str (prn form))))
                     e))))))
      (in-ns 'com.mdelaurentis.coverage)
      (apply clojure.test/run-tests (map symbol namespaces))
      (when output
        (let [stats (gather-stats @*covered*)]
          (with-out-writer "/Users/mdelaurentis/src/clojure-test-coverage/foo"
            #_(prn stats)
            #_(doseq [form @*covered*]
              (prn form (:original (meta form)))))
          (report output stats))))))

(when-not *compile-files*
  (apply -main *command-line-args*))