(ns cloverage.instrument
  (:use [slingshot.slingshot :only [throw+]]
        [clojure.java.io :only [writer]]
        [clojure.string  :only [split]]
        [cloverage debug source])
  (:require [clojure.set :as set]
            [clojure.test :as test]
            [clojure.tools.logging :as log]
            [clojure.tools.reader :as r]
            [riddley.walk :refer [macroexpand-all]]))

(defn iobj? [form]
  (and
    (instance? clojure.lang.IObj form)
    (not (instance? clojure.lang.AFunction form))))

(defn propagate-line-numbers
  "Assign :line metadata to all possible elements in a form,
   using start as default."
  [start form]
  (if (iobj? form)
    (let [line (or (:line (meta form)) start)
          recs (if (and (seq? form) (seq form))
                 ;; (seq '()) gives nil which makes us NPE. Bad.
                 (with-meta
                   (seq (map (partial propagate-line-numbers line) form))
                   (meta form))
                 form)
          ret  (if (and line
                        (not (number? (:line (meta form)))))
                 (vary-meta recs assoc :line line)
                 recs)]
      ret)
    form))

(defn add-original [old new]
  (if (iobj? new)
    (let [res      (-> (propagate-line-numbers (:line (meta old)) new)
                       (vary-meta merge (meta old))
                       (vary-meta assoc :original old))]
      res)
    new))

(defn atomic-special? [sym]
  (contains? '#{quote var clojure.core/import* recur} sym))

;; Snipped from tools.reader
;; https://github.com/clojure/tools.reader/blob/de7b39c3/src/main/clojure/clojure/tools/reader.clj#L456
(defn- resolve-ns [sym]
  (or ((ns-aliases *ns*) sym)
      (find-ns sym)))

(defn- resolve-symbol [s]
  (if (pos? (.indexOf (name s) "."))
    s
    (if-let [ns-str (namespace s)]
      (let [ns (resolve-ns (symbol ns-str))]
        (if (or (nil? ns)
                (= (name (ns-name ns)) ns-str)) ;; not an alias
          s
          (symbol (name (.name ns)) (name s))))
      (if-let [o ((ns-map *ns*) s)]
        (if (class? o)
          (symbol (.getName ^Class o))
          (if (var? o)
            (symbol (-> o .ns .name name) (-> o .sym name))))
        ;; changed to returned unnamespaced symbol if it fails to resolve
        s))))

(defn- maybe-resolve-symbol [expr]
  (if (symbol? expr)
    (resolve-symbol expr)
    expr))

(defn list-type [head]
  (condp #(%1 %2) (maybe-resolve-symbol head)
    ;; namespace-less specials
    '#{try}         :try     ; try has to special case catch/finally
    '#{if do throw} :do      ; these special forms can recurse on all args
    '#{let* loop*}  :let
    '#{def}         :def     ; def can recurse on initialization expr
    '#{fn*}         :fn
    '#{set!}        :set     ; set must not evaluate the target expr
    '#{.}           :dotjava
    '#{case*}       :case*
    '#{new}         :new
    ;; FIXME: monitor-enter monitor-exit
    ;; FIXME: import*?
    ;; FIXME: deftype*

    ;; namespaced macros
    `#{cond}        :cond    ; special case cond to avoid false partial
    `#{loop let}    :let
    `#{letfn}       :letfn
    `#{fn}          :fn
    `#{defn}        :defn    ; don't expand defn to preserve stack traces
    `#{defmulti}    :defmulti ; special case defmulti to avoid boilerplate
    `#{defprotocol} :atomic   ; no code in protocols
    `#{defrecord}   :record
    `#{ns}          :atomic

    ;; http://dev.clojure.org/jira/browse/CLJ-1330 means AOT-compiled definlines
    ;; are broken when used indirectly. Work around - do not wrap the definline
    `#{booleans bytes chars shorts floats ints doubles longs} :inlined
    atomic-special?   :atomic
    ;; XXX: we used to not do anything with unknown specials, now we wrap them
    ;; in a macro, then macroexpand back to original form. Methinks it's ok.
    special-symbol?   :unknown
    (constantly true) :list))

(defn list-type-in-env [[head & _] env]
  (if (get env head)
    :list ; local variables can't be macros/special symbols
    (list-type head)))

(defn form-type
  "Classifies the given form"
  ([form env]
   (let [res (cond (seq? form)  (list-type-in-env form env)
                   (coll? form) :coll
                   :else        :atomic)]
     (tprnl "Type of" (class form) form "is" res)
     (tprnl "Meta of" form "is" (meta form))
     res)))

(defn- var->sym [fvar]
  (let [it (name (.sym fvar))
        nsn (name (ns-name (.ns fvar)))]
    (symbol nsn it)))

(defmulti do-wrap
  "Traverse the given form and wrap all its sub-forms in a function that evals
   the form and records that it was called."
  (fn [f line form env]
    (form-type form env)))

(defmacro wrapm
  "Helper macro for wrap.
  Takes advantage of &env to track lexical scope while walking `form`."
  [f-sym line-hint form]
  (let [f    (resolve f-sym)
        line (or (:line (meta form)) line-hint)
        result (do-wrap f line form &env)]
    result))

(defn wrap
  "Main interface for wrapping expressions using `f`.
  Wrap will return a form that during macroexpansion calls `f` on `form` and
  all sub-expressions of `form` that can be meaningfully wrapped.
  `f` should take an expression and return one that evaluates in exactly the
  same way, possibly with additional side effects."
  [f-var line-hint form]
  (when-not (var? f-var)
    (throw (Exception. (str "Wrap must be given a function var. Got " f-var " [" (type f-var) "] instead."))))
  `(wrapm ~(var->sym f-var) ~line-hint ~form))

(defn wrapper
  "Return a function that when called, wraps f through its argument."
  [f line]
  (partial wrap f line))

(defn wrap-binding [f line-hint [args & body :as form]]
  "Wrap a let/loop binding

   e.g. - `a (+ a b)`       (let or loop)"
  (tprnl "Wrapping overload" args body)
  (let [line (or (:line (meta form)) line-hint)]
    (let [wrapped (doall (map (wrapper f line) body))]
      `(~args ~@wrapped))))

(defn wrap-overload [f line-hint [args & body :as form]]
  "Wrap a single function overload.

   e.g. - ([a b] (+ a b)) or
          ([n] {:pre [(> n 0)]} (/ 1 n))"
   (tprnl "Wrapping function overload" args body)
   (let [line  (or (:line (meta form)) line-hint)
         conds (when (and (next body) (map? (first body)))
                 (first body))
         conds (when conds
                 (zipmap (keys conds)
                         (map (fn [exprs] (vec (map (wrapper f line) exprs)))
                              (vals conds)))) ; must not wrap the vector itself
         ;; i.e. [(> n 1)] -> [(do (> n 1))], not (do [...])
         ;; the message of AssertionErrors will be different, too bad.
         body  (if conds (next body) body)
         wrapped (doall (map (wrapper f line) body))]
     `(~args
        ~@(when conds (list conds))
        ~@wrapped)))

;; Wrap a list of function overloads, e.g.
;;   (([a] (inc a))
;;    ([a b] (+ a b)))
(defn wrap-overloads [f line-hint form]
  (tprnl "Wrapping overloads " form)
  (let [line (or (:line (meta form)) line-hint)]
    (if (vector? (first form))
      (wrap-overload f line form)
      (try
       (doall (map (partial wrap-overload f line) form))
       (catch Exception e
         (tprnl "ERROR: " form)
         (tprnl e)
         (throw
           (Exception. (pr-str "While wrapping" (:original (meta form)))
                       e)))))))

;; Don't wrap or descend into unknown forms
(defmethod do-wrap :unknown [f line form _]
  (log/warn (str "Unknown special form " (seq form)))
  form)

;; Don't wrap definline functions - see http://dev.clojure.org/jira/browse/CLJ-1330
(defmethod do-wrap :inlined [f line [inline-fn & body] _]
  `(~inline-fn ~@(map (wrapper f line) body)))

;; Don't descend into atomic forms, but do wrap them
(defmethod do-wrap :atomic [f line form _]
  (f line form))

;; Only here for Clojure 1.4 compatibility, 1.6 has record?
(defn- map-record? [x]
  (instance? clojure.lang.IRecord x))

;; For a collection, just recur on its elements.
(defmethod do-wrap :coll [f line form _]
  (tprn ":coll" form)
  (let [wrappee (map (wrapper f line) form)
        wrapped (cond (vector? form) `[~@wrappee]
                      (set? form) `#{~@wrappee}
                      (map-record? form) (merge form
                                           (zipmap
                                             (doall (map (wrapper f line) (keys form)))
                                             (doall (map (wrapper f line) (vals form)))))
                      (map? form) (zipmap
                                   (doall (map (wrapper f line) (keys form)))
                                   (doall (map (wrapper f line) (vals form))))
                      :else (do
                              (when (nil? (empty form))
                                (throw+ (str "Can't construct empty " (class form))))
                              `(into ~(empty form) [] ~(vec wrappee))))]
    (tprn ":wrapped" (class form) (class wrapped) wrapped)
    (f line wrapped)))

(defn wrap-fn-body [f line form]
 (let [fn-sym (first form)
       res    (if (symbol? (second form))
                ;; If the fn has a name, include it
                `(~fn-sym ~(second form)
                          ~@(wrap-overloads f line (rest (rest form))))
                `(~fn-sym ~@(wrap-overloads f line (rest form))))]
    (tprnl "Instrumented function" res)
    res))

;; Wrap a fn form
(defmethod do-wrap :fn [f line form _]
  (tprnl "Wrapping fn " form)
  (f line (wrap-fn-body f line form)))

(defmethod do-wrap :let [f line [let-sym bindings & body :as form] _]
  (f line
   `(~let-sym
     [~@(mapcat (partial wrap-binding f line)
                (partition 2 bindings))]
      ~@(doall (map (wrapper f line) body)))))

(defmethod do-wrap :letfn [f line [_ bindings & _ :as form] _]
  ;; (letfn [(foo [bar] ...) ...] body) ->
  ;; (letfn* [foo (fn foo [bar] ...) ...] body)
  ;; must not wrap (fn foo [bar] ...)
  ;; we expand it manually to preserve function lines
  (let [[letfn*-sym exp-bindings & body] (macroexpand-1 form)]
    (f line
       `(~letfn*-sym
          [~@(mapcat
               (fn [[sym fun] orig-bind]
                 `(~sym ~(wrap-fn-body f (:line (meta orig-bind)) fun)))
               (partition 2 exp-bindings)
               bindings)]
          ~@(doall (map (wrapper f line) body))))))

(defmethod do-wrap :def [f line [def-sym name & body :as form] _]
  (cond
    (empty? body)      (f line `(~def-sym ~name))
    (= 1 (count body)) (let [init (first body)]
                         (f line
                            `(~def-sym ~name ~(wrap f line init))))
    (= 2 (count body)) (let [docstring (first body)
                             init      (second body)]
                         (f line
                            `(~def-sym ~name ~docstring ~(wrap f line init))))))

(defmethod do-wrap :defn [f line form _]
  ;; do not wrap fn expressions in (defn name (fn ...))
  ;; to preserve function names in exception backtraces
  (let [[def-sym name fn-expr] (macroexpand-1 form)]
    (f line `(~def-sym ~name ~(wrap-fn-body f line fn-expr)))))

(defmethod do-wrap :new [f line [new-sym class-name & args :as form] _]
  (f line `(~new-sym ~class-name ~@(doall (map (wrapper f line) args)))))

(defmethod do-wrap :dotjava [f line [dot-sym obj-name attr-name & args :as form] env]
  ;; either (. obj meth args*) or (. obj (meth args*))
  ;; I think we might have to not-wrap symbols here, or we might lose metadata
  ;; (like :tag type hints for reflection when resolving methods)
  (if (= :list (form-type attr-name env))
    (do
      (tprnl "List dotform, recursing on" (rest attr-name))
      (f line `(~dot-sym ~obj-name (~(first attr-name)
                                    ~@(doall (map (wrapper f line)
                                                  (rest attr-name)))))))
    (do
      (tprnl "Simple dotform, recursing on" args)
      (f line `(~dot-sym ~obj-name ~attr-name ~@(doall (map (wrapper f line) args)))))))

(defmethod do-wrap :set [f line [set-symbol target expr] _]
  ;; target cannot be wrapped or evaluated
  (f line `(~set-symbol ~ target ~(wrap f line expr))))

(defmethod do-wrap :do [f line [do-symbol & body] _]
  (f line `(~do-symbol ~@(map (wrapper f line) body))))

(defmethod do-wrap :cond [f line [cond-symbol & body :as form] _]
  (if (and (= 2 (count body))
           (= :else (first body)))
    (f line (macroexpand `(~cond-symbol :else ~(wrap f line (second body)))))
    (wrap f line (macroexpand form))))

(defmethod do-wrap :case* [f line [case-symbol test-var a b else-clause case-map & stuff] _]
  (assert (= case-symbol 'case*))
  (let [wrap-it (wrapper f line)
        wrapped-else (wrap-it else-clause)
        wrapped-map (into (empty case-map)
                          (zipmap (keys case-map)
                                  (for [[k exp] (vals case-map)]
                                    [k (wrap-it exp)])))]
    (f line `(~case-symbol ~test-var ~a ~b ~wrapped-else
                           ~wrapped-map ~@stuff))))

(defn wrap-catch [f line [catch-symbol classname localname & body]]
  ;; can't transform into (try (...) (<capture> (finally ...)))
  ;; catch/finally must be direct children of try
  `(~catch-symbol ~classname ~localname ~@(map (wrapper f line) body)))

(defn wrap-finally [f line [finally-symbol & body]]
  ;; can't transform into (try (...) (<capture> (finally ...)))
  ;; catch/finally must be direct children of try
  `(~finally-symbol ~@(map (wrapper f line) body)))

(defmethod do-wrap :try [f line [try-symbol & body] _]
  (f line `(~try-symbol
             ~@(map (fn wrap-try-body [elem]
                      (if-not (seq? elem)
                        (f line elem)
                        (let [head (first elem)]
                          (cond
                            (= head 'finally) (wrap-finally f line elem)
                            (= head 'catch)   (wrap-catch f line elem)
                            :else             (wrap f line elem)))))
                    body))))

(defmethod do-wrap :list [f line form env]
  (tprnl "Wrapping " (class form) form)
  (let [expanded (macroexpand form)]
    (tprnl "Expanded" form "into" expanded)
    (tprnl "Meta on expanded is" (meta expanded))
    (if (= :list (form-type expanded env))
      (let [wrapped (doall (map (wrapper f line) expanded))]
        (f line (add-original form wrapped)))
      (wrap f line (add-original form expanded)))))

(defn wrap-record-spec [f line-hint [meth-name args & body :as form]]
  (let [line (or (:line (meta form)) line-hint)]
    `(~meth-name ~args ~@(map (wrapper f line) body))))

(defmethod do-wrap :record [f line [defr-symbol name fields & opts+specs] _]
  ;; (defrecord name [fields+] options* specs*)
  ;; currently no options
  ;; spec == thing-being-implemented (methodName [args*] body)*
  ;; we only want to recurse on the methods
  (let [specs (map #(if (seq? %) (wrap-record-spec f line %) %) opts+specs)]
    (f line `(~defr-symbol ~name ~fields ~@specs))))

(defmethod do-wrap :defmulti [f line [defm-symbol name & other] _]
  ;; wrap defmulti to avoid partial coverage warnings due to internal
  ;; clojure code (stupid checks for wrong syntax)
  (let [docstring     (if (string? (first other)) (first other) nil)
        other         (if docstring (next other) other)
        attr-map      (if (map? (first other)) (first other) nil)
        other         (if (map? (first other)) (next other) other)
        dispatch-form (first other)
        other         (rest other)]
  (f line `(~defm-symbol ~name ~@(if docstring (list docstring) (list))
                               ~@(if attr-map  (list attr-map)  (list))
                               ~(wrap f line dispatch-form) ~@other))))

(defn instrument
  "Instruments and evaluates a list of forms."
  ([f-var lib]
    (let [filename (resource-path lib)]
      (let [src (form-reader lib)]
        (loop [instrumented-forms nil]
          (if-let [form (binding [*read-eval* false]
                          (r/read {:eof nil
                                   :features #{:clj}
                                   :read-cond :allow}
                                  src))]
            (let [line-hint (:line (meta form))
                  form      (if (and (iobj? form)
                                     (nil? (:file (meta form))))
                              (vary-meta form assoc :file filename)
                              form)
                  wrapped   (try
                              (wrap f-var line-hint form)
                              (catch Throwable t
                                (throw+ t "Couldn't wrap form %s at line %s"
                                          form line-hint)))]
              (try
                (binding [*file*        filename
                          *source-path* filename]
                  (eval wrapped))
                (binding [*print-meta* true]
                  (tprn "Evalling" wrapped " with meta " (meta wrapped)))
                (catch Exception e
                  (throw (Exception.
                           (str "Couldn't eval form "
                                (binding [*print-meta* true]
                                  (with-out-str (prn wrapped)))
                                (with-out-str (prn (macroexpand-all wrapped)))
                                (with-out-str (prn form)))
                           e))))
              (recur (conj instrumented-forms wrapped)))
            (do
              (let [rforms (reverse instrumented-forms)]
                (dump-instrumented rforms lib)
                rforms))))))))

(defn nop
  "Instrument form with expressions that do nothing."
  [line-hint form]
  `(do ~form))

(defn no-instr
  "Do not change form at all."
  [line-hint form]
  form)
