(ns com.mdelaurentis.coverage
  (:import [clojure.lang LineNumberingPushbackReader])
  (:use [clojure test]
        [clojure.contrib.duck-streams :only [reader]]))

(def *lines-covered* (ref (sorted-set)))
(def *forms-covered* (ref #{}))
(def *forms* (ref {}))

(defmacro capture2 [line form]
  (let [text (with-out-str (prn form))]
    (println "Form is " (meta form) text)
    `(do (println "I am running" ~line (str ~text))
         (dosync (commute *lines-covered* conj ~line))
         ~form)))

(defn wrap [thing]
  (println "Wrapping" (:line (meta thing)) thing)
  (if (and (list? thing)
           (not (= 'ns (first thing))))
    (let [wrapped (list 'com.mdelaurentis.coverage/capture2 (:line (meta thing)) (map wrap thing))] 
      (println "mapped it:" wrapped)
      (dosync (commute *forms* assoc wrapped false))
      wrapped)
    thing))

(defn instrument [file]
  (reverse
   (with-open [in (LineNumberingPushbackReader. (reader file))]
     (loop [forms nil]
       (let [form (read in false nil true)]
         (if form
           (recur (conj forms (wrap form)))
           forms))))))

(defn capture [form]
  (dosync (commute *lines-covered* conj (:line (meta form)))
          (commute *forms-covered* conj form)
          (commute com.mdelaurentis.coverage/*forms* assoc form true))
  (prn "I am running"  form)
  (eval form))

(defn analyze [file]
  (binding [*lines-covered* (ref (sorted-set))])
  (doseq [form (instrument file)]
    (println "Evaling " form)
    (eval form))
  (with-open [in (LineNumberingPushbackReader. (reader file))]
    (loop [line (.readLine in)]
      (when line
        (let [sym (if (*lines-covered* (dec (.getLineNumber in))) "+" " ")]
          (println sym (str line))
          (recur (.readLine in))))))
  @*lines-covered*)


(analyze "/Users/mdelaurentis/src/clojure-test-coverage/src/com/mdelaurentis/sample.clj")
