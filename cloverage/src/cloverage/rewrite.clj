(ns cloverage.rewrite)

(defn better-empty [x]
  (with-meta
    (if (instance? clojure.lang.IMapEntry x)
      []
      (empty x))
    (meta x)))

(defn better-into [e s]
  (if (list? e)
    (with-meta
      (seq s)
      (meta e))
    (into e s)))

(defn walk
  [inner outer form]
  (if (coll? form)
    (let [e (better-empty form)
          contents (map inner form)]
      (outer (better-into e contents)))
    (outer form)))

(defn postwalk
  [f form]
  (walk (partial postwalk f) f form))

(defn prewalk
  [f form]
  (walk (partial prewalk f) identity (f form)))

(defn do-walk [pred f forms]
  (prewalk
   (fn [x] (if (pred x) (f x) x))
   forms))

(defn cs? [s]
  (chunked-seq? s))

(defn chunked-if? [form]
  (and
   (seq? form)
   (= (first form) 'if)
   (seq? (second form))
   (= (first (second form)) `chunked-seq?)))

(defn unchunk [forms]
  (->>
   forms
   (do-walk `#{chunked-seq?} (constantly `cs?))
   macroexpand
   (do-walk chunked-if? #(nth % 3))))
