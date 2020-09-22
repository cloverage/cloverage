(ns cloverage.instrument
  (:require [clojure.pprint :as pprint]
            [clojure.tools.logging :as log]
            [cloverage.debug :as d]
            [cloverage.rewrite :refer [unchunk]]
            [cloverage.source :as source]
            [riddley.walk :as rw]
            [slingshot.slingshot :refer [throw+]]))

(defn- iobj? [form]
  (and
   (instance? clojure.lang.IObj form)
   (not (instance? clojure.lang.AFunction form))))

(defn- propagate-line-numbers
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

(defn- add-original [old new]
  (if (iobj? new)
    (-> (propagate-line-numbers (:line (meta old)) new)
        (vary-meta merge (meta old))
        (vary-meta assoc :original old))
    new))

(defn- atomic-special? [sym]
  (contains? '#{quote var clojure.core/import* recur} sym))

(defn- inlined-fn-call?
  "Whether a form represents a call to a inlineable function (e.g. `clojure.core/int`) and will be replaced with the
  inlined version."
  [[head & args]]
  (when (symbol? head)
    (when-let [{:keys [inline inline-arities]} (meta (resolve head))]
      (when inline
        ;; some inlineable function calls such as to `+` are only replaced with the inlined version for certain arities
        ;; -- the `:inline-arities` function in the metadata can be used to check whether the call will be inlined
        (if inline-arities
          (inline-arities (count args))
          true)))))

;; Snipped from tools.reader
;; https://github.com/clojure/tools.reader/blob/de7b39c3/src/main/clojure/clojure/tools/reader.clj#L456
(defn- resolve-ns [sym]
  (or ((ns-aliases *ns*) sym)
      (find-ns sym)))

(defn- resolve-symbol
  "Attempt to resolve symbol `s` in the current namespace, returning a namespace-qualified symbol if resolved, otherwise
  returning the `s` as-is."
  [s]
  (if (pos? (.indexOf (name s) "."))
    s
    (if-let [ns-str (namespace s)]
      (let [ns (resolve-ns (symbol ns-str))]
        (if (or (nil? ns)
                (= (name (ns-name ns)) ns-str)) ;; not an alias
          s
          (symbol (name (ns-name ns)) (name s))))
      (if-let [o ((ns-map *ns*) s)]
        (if (class? o)
          (symbol (.getName ^Class o))
          (if (var? o)
            (let [^clojure.lang.Var o o]
              (symbol (-> o .ns .name name) (-> o .sym name)))))
        ;; changed to returned unnamespaced symbol if it fails to resolve
        s))))

(defn- maybe-resolve-symbol [expr]
  (cond
    (special-symbol? expr)
    expr

    (symbol? expr)
    (resolve-symbol expr)

    :else
    expr))

(def ^:private list-head-symbol->type
  "Map of symbols to the type of form they represent when they're at the head of a list or list-like form.

    {'do :do, 'let* :let, 'try :try, ...}"
  (into {} (for [[symbols typ]
                 ;; namespace-less symbols
                 {'#{try}         :try  ; try has to special case catch/finally
                  '#{if do throw} :do   ; these special forms can recurse on all args
                  '#{let* loop*}  :let
                  '#{def}         :def  ; def can recurse on initialization expr
                  '#{fn*}         :fn
                  '#{set!}        :set  ; set must not evaluate the target expr
                  '#{.}           :dotjava
                  '#{case*}       :case*
                  '#{new}         :new
                  '#{reify*}      :reify*
                  ;; FIXME: monitor-enter monitor-exit

                  ;; namespaced macros
                  `#{cond}        :cond ; special case cond to avoid false partial
                  `#{loop let}    :let
                  `#{letfn}       :letfn
                  `#{for doseq}   :for
                  `#{fn}          :fn
                  `#{defn}        :defn     ; don't expand defn to preserve stack traces
                  `#{defmulti}    :defmulti ; special case defmulti to avoid boilerplate
                  `#{defprotocol} :atomic   ; no code in protocols
                  `#{defrecord}   :record
                  `#{deftype*}    :deftype*
                  `#{ns}          :atomic

                  ;; http://dev.clojure.org/jira/browse/CLJ-1330 means AOT-compiled definlines
                  ;; are broken when used indirectly. Work around - do not wrap the definline
                  `#{booleans bytes chars shorts floats ints doubles longs} :definlined-fn-call}
                 symb symbols]
             [symb typ])))

(defn- list-type
  "Return the type of a list or list-like (presumably a function/macro call) form.

    (list-type '(= 1 2)) ; -> :list
    (list-type '(fn [])) ; -> :fn"
  ([[head :as form]]
   (let [head (maybe-resolve-symbol head)]
     (or (list-head-symbol->type head)
         (cond
           ;; A call to a function such as `clojure.core/int` that is defined normally with `defn` but has a special
           ;; `:inline` version. Not necessarily inlined for all arities -- for example `+` is only inlined for 2+ args
           (inlined-fn-call? form)  :inlined-fn-call
           ;; A special form such as `quote` or `import*`
           (atomic-special? head)   :atomic
           ;; XXX: we used to not do anything with unknown specials, now we wrap them
           ;; in a macro, then macroexpand back to original form. Methinks it's ok.
           (special-symbol? head)   :unknown
           :else                    :list))))

  ([[head :as form] env]
   ;; if the list is a call to a symbol with a local binding, but not a special form such as `var` or `new` (which
   ;; aren't overshadowed by local variables) return `:list` type.
   (if (and (get env head)
            (not (special-symbol? head)))
     :list
     (list-type form))))

(def ^:dynamic *exclude-calls*
  "The set of symbols that will suppress instrumentation when any are used in
  the calling position of a form. Useful for excluding problematic macro call
  sites from coverage metrics."
  nil)

(defn exclude?
  "Whether we should skip instrumenting a form because it is specified in the `:exclude-call` options."
  [form]
  (boolean (and *exclude-calls*
                (*exclude-calls* (maybe-resolve-symbol (first form))))))

(defn form-type
  "Classifies the given form"
  [form env]
  (let [res (cond (and (seq? form)
                       (exclude? form)) :excluded
                  (seq? form)           (list-type form env)
                  (coll? form)          :coll
                  :else                 :atomic)]
    (d/tprf "Type of %s %s is %s\n"
            (some-> form class .getCanonicalName)
            (str (when (meta form)
                   (format "^%s " (pr-str (meta form))))
                 (pr-str form))
            res)
    res))

(defn- var->sym [^clojure.lang.Var fvar]
  (let [it (name (.sym fvar))
        nsn (name (ns-name (.ns fvar)))]
    (symbol nsn it)))

(defmulti do-wrap
  "Traverse the given form and wrap all its sub-forms in a function that evals
  the form and records that it was called."
  {:arglists '([f line form env])}
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

(defn wrap-binding
  "Wrap a let/loop binding

  e.g. - `a (+ a b)`       (let or loop)"
  [f line-hint [args & body :as form]]
  (d/tprnl "Wrapping overload" args body)
  (let [line (or (:line (meta form)) line-hint)]
    (let [wrapped (doall (map (wrapper f line) body))]
      `(~args ~@wrapped))))

(defn wrap-overload
  "Wrap a single function overload.

  e.g. - ([a b] (+ a b)) or
          ([n] {:pre [(> n 0)]} (/ 1 n))"
  [f line-hint [args & body :as form]]
  (d/tprnl "Wrapping function overload" args body)
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
  (d/tprnl "Wrapping overloads " form)
  (let [line (or (:line (meta form)) line-hint)]
    (if (vector? (first form))
      (wrap-overload f line form)
      (try
        (doall (map (partial wrap-overload f line) form))
        (catch Exception e
          (d/tprnl "ERROR: " form)
          (d/tprnl e)
          (throw
           (Exception. (pr-str "While wrapping" (:original (meta form)))
                       e)))))))

;; Don't wrap or descend into unknown forms
(defmethod do-wrap :unknown [f line form _]
  (log/warn (str "Unknown special form " (seq form)))
  form)

;; Don't wrap or descend into excluded forms
(defmethod do-wrap :excluded [_ _ form _]
  (log/info (str "Excluded form " (seq form)))
  form)

;; Don't wrap definline functions - see http://dev.clojure.org/jira/browse/CLJ-1330
(defmethod do-wrap :definlined-fn-call [f line [inline-fn & body] _]
  `(~inline-fn ~@(map (wrapper f line) body)))

;; Other functions that are defined via `defn` but that define special `:inline` versions in their metadata, such as
;; `clojure.core/int`, should get expanded to the inlined versions before instrumentation. Riddley can expand it for
;; us
(defmethod do-wrap :inlined-fn-call
  [f line form _]
  ;; there's a bug in Riddley where the last form in an inlined call nested inside another inlined call can get marked
  ;; as `::rw/transformed` preventing it from being expanded completely -- see
  ;; https://github.com/ztellman/riddley/issues/33. We can work around it by removing the `::rw/transformed` key from
  ;; the form's metadata if present
  (let [form (vary-meta form dissoc ::rw/transformed)]
    (wrap f line (rw/macroexpand form))))

;; Don't descend into atomic forms, but do wrap them
(defmethod do-wrap :atomic [f line form _]
  (f line form))

;; Only here for Clojure 1.4 compatibility, 1.6 has record?
(defn- map-record? [x]
  (instance? clojure.lang.IRecord x))

;; For a collection, just recur on its elements.
(defmethod do-wrap :coll [f line form _]
  (d/tprn ":coll" form)
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
    (d/tprn ":wrapped" (class form) (class wrapped) wrapped)
    (f line (vary-meta wrapped merge (meta form)))))

(defn wrap-fn-body [f line form]
  (let [fn-sym (first form)
        res    (if (symbol? (second form))
                ;; If the fn has a name, include it
                 `(~fn-sym ~(second form)
                           ~@(wrap-overloads f line (rest (rest form))))
                 `(~fn-sym ~@(wrap-overloads f line (rest form))))]
    (d/tprnl "Instrumented function" res)
    (vary-meta res (partial merge (meta form)))))

;; Wrap a fn form
(defmethod do-wrap :fn [f line form _]
  (d/tprnl "Wrapping fn " form)
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

(defmethod do-wrap :dotjava
  [f line [_ class-or-instance & more] env]
  ;; form is either of the syntax
  ;;
  ;; (. class-or-instance method & args)
  ;; or
  ;; (. class-or-instance (method & args))
  ;;
  ;; both syntaxes are possible and equivalent
  ;;
  ;; If `class-or-instance` is a symbol (i.e., a class name) or a class itself, don't wrap it so we don't lose type
  ;; information
  (let [wrapped-class-or-instance (if (or (symbol? class-or-instance)
                                          (class? class-or-instance))
                                    class-or-instance
                                    (wrap f line class-or-instance))]
    (f
     line
     (if (seq? (first more))
       ;; (. class-or-instance (method & args))
       (let [[[method & args]] more]
         (list '. wrapped-class-or-instance (cons method (doall (map (wrapper f line) args)))))
       ;; (. class-or-instance method & args)
       (let [[method & args] more]
         (list* '. wrapped-class-or-instance method (doall (map (wrapper f line) args))))))))

(defmethod do-wrap :set [f line [set-symbol target expr] _]
  ;; target cannot be wrapped or evaluated
  (f line `(~set-symbol ~target ~(wrap f line expr))))

(defmethod do-wrap :do
  [f line [do-symbol & body] env]
  (f line (cons do-symbol (for [form body]
                            (do-wrap f (or (:line (meta form)) line) form env)))))

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

(defmethod do-wrap :for [f line form env]
  (do-wrap f line (unchunk form) env))

(defn- propagate-fn-call-tag
  "Propagate type `:tag` metadata from the symbol in function position in the original form to the resulting
  instrumented form e.g.

    (propagate-fn-call-tag (str \"Ok\") (do (do str) (do \"Ok\")))
    ;; -> ^String (do (do str) (do \"Ok\"))

  This is done so the resulting instrumented form will have the same type tag information as the compiler would have
  inferred from the original non-instrumented version."
  [[head :as original-form] instrumented-form]
  (let [form-tag (some (comp :tag meta)
                       [original-form
                        (when (symbol? head) (resolve head))
                        head])]
    (d/tprf "Propagating tag %s from form %s\n" (pr-str form-tag) (pr-str original-form))
    (cond-> instrumented-form
      form-tag (vary-meta update :tag (fn [existing-tag]
                                        (or existing-tag form-tag))))))

(defmethod do-wrap :list
  [f line form env]
  (d/tprnl "Wrapping list" (class form) (pr-str form))
  (let [expanded (macroexpand-1 form)]
    (if (identical? form expanded)
      ;; if this list is *not* a macro form, then recursively wrap each item in the list.
      (let [wrapped (->> (doall (map (wrapper f line) form))
                         (add-original form)
                         (propagate-fn-call-tag form))]
        (d/tprf "Wrapped list ^%s %s\n-> ^%s %s\n" (pr-str (meta form)) (pr-str form)
                (pr-str (meta wrapped)) (pr-str wrapped))
        (f line wrapped))
      ;; if list is a macro call: recursively wrap the entire expanded form
      (do
        (d/tprf "Expanded ^%s %s into ^%s %s\n"
                (pr-str (meta form)) (pr-str form)
                (pr-str (meta expanded)) (pr-str expanded))
        (do-wrap f line (add-original form expanded) env)))))

(defn wrap-deftype-defrecord-method [f line [meth-name args & body :as method-form]]
  (let [method-line (or (:line (meta method-form)) line)
        body        (for [form body
                          :let [line (or (:line (meta form)) method-line)]]
                      (wrap f line form))]
    `(~meth-name ~args ~@body)))

(defmethod do-wrap :record
  [f line [defr-symbol name fields & opts+specs] _]
  ;; (defrecord name [fields*] options* specs*)
  ;;
  ;; spec == thing-being-implemented (methodName [args*] body)*
  ;; we only want to recurse on the methods
  (let [wrapped-opts+specs (for [opt-or-spec opts+specs]
                             (if (seq? opt-or-spec)
                               (wrap-deftype-defrecord-method f line opt-or-spec)
                               opt-or-spec))]
    (f line `(~defr-symbol ~name ~fields ~@wrapped-opts+specs))))

(defmethod do-wrap :deftype* [f line [deft-symbol name class-name fields implements interfaces & methods] _]
  ;; (deftype name [fields*] options* specs*)
  ;;
  ;; expands into
  ;;
  ;; (deftype* name class-name [fields*] :implements [interfaces] methods*)
  (let [wrapped-methods (for [method methods]
                          (wrap-deftype-defrecord-method f line method))]
    `(~deft-symbol ~name ~class-name ~fields ~implements ~interfaces ~@wrapped-methods)))

(defmethod do-wrap :defmulti [f line [defm-symbol name & other] _]
  ;; wrap defmulti to avoid partial coverage warnings due to internal
  ;; clojure code (stupid checks for wrong syntax)
  (let [docstring     (when (string? (first other)) (first other))
        other         (if docstring (next other) other)
        attr-map      (when (map? (first other)) (first other))
        other         (if (map? (first other)) (next other) other)
        dispatch-form (first other)
        other         (rest other)]
    (f line `(~defm-symbol ~name ~@(if docstring (list docstring) (list))
                           ~@(if attr-map  (list attr-map)  (list))
                           ~(wrap f line dispatch-form) ~@other))))

(defmethod do-wrap :reify* [f line [reify-symbol interfaces & methods] _]
  (f line `(~reify-symbol
            ~interfaces
            ~@(map (fn wrap-reify-method [method]
                     `(~(first method) ~@(wrap-overload f line (rest method))))
                   methods))))

;; `f-var` used below is a var referring to a function with the form
;;
;;    (fn [line-hint form])
;;
;; used to perform the instrumentation

(defn eval-form
  "Evaluate an `instrumented-form`."
  [filename form line-hint instrumented-form]
  (try
    (binding [*file*        filename
              *source-path* filename]
      (eval instrumented-form))
    (binding [*print-meta* true]
      (d/tprn "Evalling" instrumented-form " with meta " (meta instrumented-form)))
    (catch Exception e
      (throw (ex-info "Error evaluating form"
                      (merge
                       {:line              line-hint
                        :filename          filename
                        :form              form
                        :namespace         (with-meta (ns-name *ns*) nil)
                        :instrumented-form instrumented-form}
                       (when-let [macroexpanded (try (rw/macroexpand-all form) (catch Throwable _))]
                         {:macroexpanded macroexpanded})
                       (when-let [macroexpanded (try (rw/macroexpand-all instrumented-form) (catch Throwable _))]
                         {:macroexpanded-instrumented macroexpanded}))
                      e)))))

(defn instrument-form
  "Instrument and evaluate a single `form`. Returns instrumented form."
  [f-var filename form]
  (let [line-hint (:line (meta form))]
    (try
      (let [form              (if (and (iobj? form)
                                       (nil? (:file (meta form))))
                                (vary-meta form assoc :file filename)
                                form)
            instrumented-form (try
                                (wrap f-var line-hint form)
                                (catch Throwable e
                                  (throw (ex-info "Error instrumenting form"
                                                  {:filename filename, :line line-hint, :form form}
                                                  e))))]
        (try
          (eval-form filename form line-hint instrumented-form)
          (catch Throwable e
            (throw (ex-info "Error evaluating instrumented form" {} e))))
        instrumented-form)
      ;; if we run into an error instrumenting a form or evaluating it, log it and return/eval the uninstrumented
      ;; form, so we can continue
      (catch Throwable e
        (log/error (.getMessage e) "\n"
                   (binding [*print-meta*                true
                             pprint/*print-right-margin* 120]
                     (with-out-str (pprint/pprint (Throwable->map e)))))
        (eval-form filename form line-hint form)
        form))))

(defn instrument-file
  "Instrument and evaluate all the forms in a file. Returns sequence of instrumented forms."
  [f-var lib filename]
  (with-open [source-reader (source/form-reader lib)]
    (transduce
     (map (partial instrument-form f-var filename))
     conj
     (source/forms source-reader))))

(defn instrument
  "Instrument and evaluate the forms in the namespace named by `ns-symbol` using an instrumentation function referred to
  by `f-var`. The instrumentation function has the signature a function with the signature

    (f line-hint form) -> instrumented-form"

  [f-var ns-symbol]
  (let [filename (source/resource-path ns-symbol)]
    (try
      (let [instrumented (instrument-file f-var ns-symbol filename)]
        (d/dump-instrumented instrumented ns-symbol)
        instrumented)
      (catch Throwable e
        (throw (ex-info (str "Error instrumenting " ns-symbol)
                        {:namespace ns-symbol
                         :filename  filename}
                        e))))))

(defn nop
  "Instrument form with expressions that do nothing."
  [_ form]
  (with-meta `(do ~form) (meta form)))

(defn no-instr
  "Do not change form at all."
  [_ form]
  form)
