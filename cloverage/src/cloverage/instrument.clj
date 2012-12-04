(ns cloverage.instrument
  (:import  [java.io InputStreamReader]
            [clojure.lang LineNumberingPushbackReader])
  (:use [slingshot.slingshot :only [throw+]]
        [clojure.java.io :only [writer]]
        [cloverage.debug])
  (:require [clojure.set :as set]
            [clojure.test :as test]
            [clojure.tools.logging :as log])

  (:gen-class))

(def ^:dynamic *instrumenting-file*)

(defn atomic-special? [sym]
  (contains? '#{quote var clojure.core/import* recur} sym))

(defn list-type [[head & _]]
  (case head
    catch                  :catch   ;; catch special cases classnames, and
    finally                :finally ;; catch and finally can't be wrapped
    set!                   :set     ;; set must not evaluate the target expr
    (if do try throw)      :do      ;; these special forms can recurse on all args
    (loop let let* loop*)  :let
    (fn fn*)               :fn
    def                    :def     ;; def can recurse on initialization expr
    .                      :dotjava
    new                    :new
    deftype*               :atomic  ;; FIXME: recurse into deftype
    clojure.core/defn      :atomic  ;; this makes defrecords work. WHY?
                                    ;; some metadata magic perhaps?
    (cond
      (atomic-special? head) :atomic
      (special-symbol? head) :unknown
      :else                  :list)))

(defn form-type
  "Classifies the given form"
  [form]
  (let [res (cond (seq? form)  (list-type form)
                  (coll? form) :coll
                  :else        :atomic)]
    (tprnl "Type of" (class form) form "is" res)
    (tprnl "Meta of" form "is" (meta form))
    res))


(defn add-line-number [hint old new]
  (if (instance? clojure.lang.IObj new)
    (let [line  (or (:line (meta new)) hint)
          recs  (if (seq? new)
                  (doall (map (partial add-line-number line old) new))
                  new)
          ret   (if line
                  (vary-meta recs assoc :line line)
                  recs)]
      ret)
    new))


(defn add-original [old new]
  (if (instance? clojure.lang.IObj new)
    (let [res (-> (add-line-number (:line (meta old)) old new)
                  (vary-meta merge (meta old))
                  (vary-meta assoc :original old))]
      res)
    new))

(defmulti do-wrap
  "Traverse the given form and wrap all its sub-forms in a function that evals
   the form and records that it was called."
  (fn [f line form]
    (form-type form)))

(defn wrap [f line-hint form]
  (let [line (or (:line (meta form)) line-hint)]
    (do-wrap f line form)))

(defn wrapper
  "Return a function that when called, wraps f through its argument."
  [f line]
  (partial wrap f line))

(defn wrap-binding [f line [args & body]]
  "Wrap a single function overload or let/loop binding

   e.g. - `([a b] (+ a b))` (defn) or
        - `a (+ a b)`       (let or loop)"
  (tprnl "Wrapping overload" args body)
  (let [wrapped (doall (map (wrapper f line) body))]
    `(~args ~@wrapped)))

;; Wrap a list of function overloads, e.g.
;;   (([a] (inc a))
;;    ([a b] (+ a b)))
(defn wrap-overloads [f line form]
  (tprnl "Wrapping overloads " form)
  (if (vector? (first form))
    (wrap-binding f line form)
    (try
     (doall (map (partial wrap-binding f line) form))
     (catch Exception e
       (tprnl "ERROR: " form)
       (tprnl e)
       (throw (Exception. (apply str "While wrapping" form) e))))))

;; Don't wrap or descend into unknown forms
(defmethod do-wrap :unknown [f line form]
  (log/warn (str "Uknown special form " (seq form)))
  form)

;; Don't descend into atomic forms, but do wrap them
(defmethod do-wrap :atomic [f line form]
  (f line form))

;; For a collection, just recur on its elements.
(defmethod do-wrap :coll [f line form]
  (tprn ":coll" form)
  (let [wrappee (map (wrapper f line) form)
        wrapped (cond (vector? form) `[~@wrappee]
                      (set? form) `#{~@wrappee}
                      (map? form) (zipmap
                                   (doall (map (wrapper f line) (keys form)))
                                   (doall (map (wrapper f line) (vals form))))
                      :else (do
                              (when (nil? (empty form))
                                (throw+ (str "Can't construct empty " (class form))))
                              `(into ~(empty form) [] ~(vec wrappee))))]
    (tprn ":wrapped" (class form) (class wrapped) wrapped)
    (f line wrapped))) ;; FIXME(alexander): this should be (f line wrapped)

(defmethod do-wrap :map [f line ])

;; Wrap a fn form
(defmethod do-wrap :fn [f line form]
  (tprnl "Wrapping fn " form)
  (let [fn-sym (first form)
        res    (if (symbol? (second form))
                 ;; If the fn has a name, include it
                 (f line `(~fn-sym ~(second form)
                              ~@(wrap-overloads f line (rest (rest form)))))
                 (f line `(~fn-sym
                      ~@(wrap-overloads f line (rest form)))))]
    (tprnl "Wrapped is" res)
    res))

(defmethod do-wrap :let [f line [let-sym bindings & body :as form]]
  (f line
   `(~let-sym
     [~@(mapcat (partial wrap-binding f line)
                (partition 2 bindings))]
      ~@(doall (map (wrapper f line) body)))))

(defmethod do-wrap :def [f line form]
  (let [def-sym (first form)
        name    (second form)]
    (if (= 3 (count form))
      (let [val (nth form 2)]
        (f line `(~def-sym ~name ~(wrap f line val))))
      (f line `(~def-sym ~name)))))

(defmethod do-wrap :new [f line [new-sym class-name & args :as form]]
  (f line `(~new-sym ~class-name ~@(doall (map (wrapper f line) args)))))

(defmethod do-wrap :dotjava [f line [dot-sym obj-name attr-name & args :as form]]
  ;; either (. obj meth args*) or (. obj (meth args*))
  (if (= :list (form-type attr-name))
    (do
      (tprnl "List dotform, recursing on" (rest attr-name))
      (f line `(~dot-sym ~obj-name (~(first attr-name)
                                    ~@(doall (map (wrapper f line) (rest attr-name)))))))
    (do
      (tprnl "Simple dotform, recursing on" args)
      (f line `(~dot-sym ~obj-name ~attr-name ~@(doall (map (wrapper f line) args)))))))

(defmethod do-wrap :set [f line [set-symbol target expr]]
  ;; target cannot be wrapped or evaluated
  (f line `(~set-symbol ~ target ~(wrap f line expr))))

(defmethod do-wrap :do [f line [do-symbol & body]]
  (f line `(~do-symbol ~@(map (wrapper f line) body))))

(defmethod do-wrap :catch [f line [catch-symbol classname localname & body]]
  ;; can't transform into (try (...) (<capture> (finally ...)))
  ;; catch/finally must be direct children of try
  `(~catch-symbol ~classname ~localname ~@(map (wrapper f line) body)))


(defmethod do-wrap :finally [f line [finally-symbol & body]]
  ;; can't transform into (try (...) (<capture> (finally ...)))
  ;; catch/finally must be direct children of try
  `(~finally-symbol ~@(map (wrapper f line) body)))

(defmethod do-wrap :list [f line form]
  (tprnl "Wrapping " (class form) form)
  (let [expanded (macroexpand form)]
    (tprnl "Expanded" form "into" expanded)
    (tprnl "Meta on expanded is" (meta expanded))
    (if (= :list (form-type expanded))
      (let [wrapped (doall (map (wrapper f line) expanded))]
        (f line (add-original form wrapped)))
      (wrap f line (add-original form expanded)))))


(defn resource-path
  "Given a symbol representing a lib, return a classpath-relative path.  Borrowed from core.clj."
  [lib]
  (tprnl "Getting resource for " lib)
  (str (.. (name lib)
           (replace \- \_)
           (replace \. \/))
       ".clj"))

(defn resource-reader [resource]
  (tprnl "Getting reader for " resource)
  (tprnl "Classpath is")
  (tprnl (seq (.getURLs (java.lang.ClassLoader/getSystemClassLoader))))
  (InputStreamReader.
   (.getResourceAsStream (clojure.lang.RT/baseLoader) resource)))

(defn instrument
  "Reads all forms from the file referenced by the given lib name, and
  returns a seq of all the forms, decorated with a function that when
  called will record the line and file of the code that was executed."
  [f lib]
  (tprnl "Instrumenting" lib)
  (when-not (symbol? lib)
    (throw+ "instrument needs a symbol"))
  (let [file (resource-path lib)]
    (binding [*instrumenting-file* lib]
      (with-open [in (LineNumberingPushbackReader. (resource-reader file))]
        (loop [forms nil]
          (if-let [form (read in false nil true)]
            (let [wrapped (try (wrap f (:line (meta form)) form)
                               (catch Throwable t
                                 (throw+ t "Couldn't wrap form %s at line %s"
                                         form (:line form))))]
              (try
               (eval wrapped)
               (tprnl "Evalled" wrapped)
               (catch Exception e
                 (throw (Exception.
                         (str "Couldn't eval form "
                              (with-out-str (prn wrapped))
                              (with-out-str (prn form)))
                         e))))
              (recur (conj forms wrapped)))
            (do
              (let [rforms (reverse forms)]
                (dump-instrumented rforms lib)
                rforms))))))))

(defn nop [line-hint form]
  `(do ~form))

(defn instrument-nop
  [lib]
  (instrument nop lib))
