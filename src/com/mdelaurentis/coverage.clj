(ns com.mdelaurentis.coverage
  (:import [clojure.lang LineNumberingPushbackReader])
  (:use [clojure test]
        [clojure.contrib.duck-streams :only [reader]]))

(def *lines-covered* (ref (sorted-set)))

(defmacro capture [line form]
  (let [text (with-out-str (prn form))]
    `(do 
       (dosync (commute *lines-covered* conj ~line))
       ~form)))

(defn wrap [thing]
  (cond 
   
   (and (list? thing) (not (= 'ns (first thing))))
   `(capture ~(:line (meta thing)) ~(map wrap thing))
   
   (vector? thing)
   (vec (map wrap thing))

   (map? thing)
   (zipmap (map wrap (keys thing))
           (map wrap (vals thing)))

    :else thing))

(defn instrument [file]
  (reverse
   (with-open [in (LineNumberingPushbackReader. (reader file))]
     (loop [forms nil]
       (let [form (read in false nil true)]
         (if form
           (recur (conj forms (wrap form)))
           forms))))))

(defn line-info [in]
  (loop [forms nil]
    (if-let [text (.readLine in)]
      (let [line (dec (.getLineNumber in))
            info   {:line line
                    :text text
                    :blank? (.isEmpty (.trim text))
                    :covered? (*lines-covered* line)}]
        (println "Got info" info)
        (recur (conj forms info)))
      (reverse forms))))


(defn analyze [file]
  (binding [*lines-covered* (ref (sorted-set))]
    ;; Eval all the forms in the file to fill up *lines-covered*
    (doseq [form (instrument file)]
      (eval form))
    (with-open [in (LineNumberingPushbackReader. (reader file))]
      (doseq [info (line-info in)]
        (let [prefix (cond (:blank? info)   "_"
                           (:covered? info) "+"
                           :else            " ")]
          (println prefix (:text info)))))))

(analyze "/Users/mdelaurentis/src/clojure-test-coverage/src/com/mdelaurentis/sample.clj")