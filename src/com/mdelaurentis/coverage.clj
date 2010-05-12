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

(def 
 #^{:doc "These are special forms that can't be evaled, so leave
them completely alone."}
 stop-symbols '#{. do if var quote try finally throw recur})

(defn form-type 
  "Classifies the given form"
  [form]
  (let [res
        (cond 

         (stop-symbols form) :stop
         
         ;; If it's a symbol, we need to see if it is a macro.  Not
         ;; sure how to find a binding for this symbol in the
         ;; namespace in which it appears, so for now check all
         ;; namespaces.
         (symbol? form) 
         (if (some #(meta ((ns-map %) form))
                   (all-ns))
           :stop
           :atomic)

         ;; These are eval'able elements that we can wrap, but can't
         ;; descend down into them.
         (string? form)  :atomic
         (number? form)  :atomic
         (keyword? form) :atomic
         (= true form)   :atomic
         (= false form)  :atomic
         (var? form)     :atomic
         (nil? form)     :atomic

         ;; Data structures
         (map?    form) :map
         (vector? form) :vector

         ;; If it's a list or a seq, check the first element to see if
         ;; it's a special form.
         (or (list? form) (seq? form))
         (let [x (first form)]
           (cond
            ;; Don't attempt to descend into these forms yet
            (= '. x) :stop
            (= 'var x) :stop
            (= 'finally x) :stop
            (= 'quote x) :stop
            (= 'deftest x) :stop ;; TODO: don't stop at deftest

            ;; fn and fn* are special forms, we need to treat their
            ;; arglists and overrides specially
            (= 'fn x)  :fn
            (= 'fn* x) :fn

            ;; let* and loop* seem to have identical syntax
            (= 'let* x)  :let
            (= 'loop* x) :let

            (= 'def x)  :def
            (= 'new x)  :new            
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

(defn track-coverage [form]
  `(capture ~(add-form form) ~form))

(defmulti wrap
  "Traverse the given form and wrap all its sub-forms in a
function that evals the form and records that it was called."
  (fn [f form]
    (form-type form)))

(defn wrapper 
  "Return a function that when called, wraps f through its argument."
  [f]
  (partial wrap f))

(defn remove-nil-line
  "Dissoc :line from the given map if it's val is nil."
  [m]
  (if (:line m)
    m
    (dissoc m :line)))

;; Don't attempt to do anything with :stop or :default forms
(defmethod wrap :stop [f form]
  form)

(defmethod wrap :default [f form]
  form)

;; Don't descend into atomic forms, but do wrap them
(defmethod wrap :atomic [f form]
  (f form))

;; For a vector, just recur on its elements.
;; TODO: May want to wrap the vector itself.
(defmethod wrap :vector [f form]
  `[~@(doall (map (wrapper f) form))])

;; Wrap a single function overload, e.g. ([a b] (+ a b))
(defn wrap-overload [f [args & body]]
  #_(println "Wrapping overload" args body)
  (let [wrapped (doall (map (wrapper f) body))]
    `([~@args] ~@wrapped)))

;; Wrap a list of function overloads, e.g. 
;;   (([a] (inc a)) 
;;    ([a b] (+ a b)))
(defn wrap-overloads [f form]
  #_(println "Wrapping overloads " form)
  (if (vector? (first form))
    (wrap-overload f form)
    (try
     (doall (map (partial wrap-overload f) form))
     (catch Exception e
       #_(println "ERROR: " form)
       (throw (Exception. (apply str "While wrapping" form) e))))))

;; Wrap a fn form
(defmethod wrap :fn [f form]
  #_(println "Wrapping fn " form)
  (let [fn-sym (first form)
        res    (if (symbol? (second form))
                 ;; If the fn has a name, include it
                 (f `(~fn-sym ~(second form)
                              ~@(wrap-overloads f (rest (rest form)))))
                 (f `(~fn-sym
                      ~@(wrap-overloads f (rest form)))))]
    #_(println "Wrapped is" res)
    res))

(defmethod wrap :let [f [let-sym bindings & body :as form]]
  (f
   `(~let-sym
     [~@(doall (mapcat (fn [[name val]] `(~name ~(wrap f val)))
                       (partition 2 bindings)))]
     ~@(doall (map (wrapper f) body)))))

;; TODO: Loop seems the same as let.  Can we combine?
(defmethod wrap :loop [f [let-sym bindings & body :as form]]
  (f
   `(~let-sym
     [~@(doall (mapcat (fn [[name val]] `(~name ~(wrap f val)))
                       (partition 2 bindings)))]
     ~@(doall (map (wrapper f) body)))))

(defmethod wrap :def [f form]
  (let [def-sym (first form)
        name    (second form)]
    (if (= 3 (count form))
      (let [val (nth form 2)]
        (f `(~def-sym ~name ~(wrap f val))))
      (f `(~def-sym ~name)))))

(defmethod wrap :new [f [new-sym class-name & args :as form]]
  (f `(~new-sym ~class-name ~@(doall (map (wrapper f) args)))))


(defmethod wrap :list [f form]
  #_(println "Wrapping " (class form) form)
  (let [expanded (macroexpand form)]
    (if (= :list (form-type expanded))
      (let [wrapped (doall (map (wrapper f) expanded))]
        `(capture ~(add-form form) ~wrapped))
      (wrap f expanded))))

(defmethod wrap :map [f form]
  (doall (zipmap (doall (map (wrapper f) (keys form)))
                 (doall (map (wrapper f) (vals form))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Reporting

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
  [f lib]
  (println "Instrumenting" lib)
  (when-not (symbol? lib)
    (throwf "instrument needs a symbol"))
  (let [file (resource-path lib)]
    (binding [*instrumenting-file* file]
      (with-open [in (LineNumberingPushbackReader. (resource-reader file))]
        (loop [forms nil]
          (if-let [form (read in false nil true)]
            (let [wrapped (try (wrap f form)
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
        (doseq [form (instrument track-coverage namespace)]
          (try
           (eval form)
           (catch Exception e
             (throw (Exception. 
                     (str "Couldn't eval form " 
                          (binding [*print-meta* false]
                            (with-out-str (prn form)))
                          "Original: " (:original form))
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

