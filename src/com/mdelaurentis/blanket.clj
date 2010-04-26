(ns com.mdelaurentis.blanket
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io File InputStreamReader])
  (:use [clojure.contrib.duck-streams :only [reader with-out-writer copy]]
        [clojure.contrib.command-line :only [with-command-line]])
  (:gen-class))

(def *covered*)

(defmacro with-coverage [files & body]
  `(binding [*covered* (ref {})]
     (println "Capturing code coverage for" ~files)
     (doseq [file# ~files]
       (instrument file#))
     ~@body
     @*covered*))

(defn covered? [coverage file line]
  ((coverage file {}) line))

(defn cover [file line]
  "Mark the given file and line in as having been covered."
  (println "I am covering" file line)
  (dosync (alter *covered* assoc-in [file line] true)))

(defmacro capture 
  "Eval the given form and record that the given line on the given
  files was run."
  [file line form]
  (let [text (with-out-str (prn form))]
    `(do 
       (cover ~file ~line)
       ~form)))

(defn wrapper
  "Returns a function that when called on some form, recursively wraps
  the form with code that will record whether the code was run.  file
  is the file that the form came from."
  [file]
  (letfn [(wrap [thing]
                (cond 
                 (and (list? thing) (not (= 'ns (first thing))))
                 `(capture ~file ~(:line (meta thing)) ~(map wrap thing))
                 
                 (vector? thing)
                 (vec (map wrap thing))

                 (map? thing)
                 (zipmap (map wrap (keys thing))
                         (map wrap (vals thing)))
             
                 :else thing))]
    wrap))

(defn resource-reader [resource]
  (InputStreamReader.
   (.getResourceAsStream (clojure.lang.RT/baseLoader) resource)))

(defn instrument
  "Reads and evals all forms in the given file, and returns a seq of
  all the forms, decorated with a function that when called will
  record the line and file of the code that was executed." 
  [file]
  (with-open [in (LineNumberingPushbackReader. (resource-reader file))]
    (let [wrap (wrapper file)]
      (loop [forms nil]
        (if-let [form (read in false nil true)]
          (let [wrapped (wrap form)]
            (do (eval wrapped)
                (recur (conj forms wrapped))))
          (reverse forms))))))

(defn line-info [file]
  (with-open [in (LineNumberingPushbackReader. (reader file))]
    (loop [forms nil]
      (if-let [text (.readLine in)]
        (let [line (dec (.getLineNumber in))
              info   {:line line
                      :text text
                      :blank? (.isEmpty (.trim text))
                      :covered? (covered? file line)}]
          (recur (conj forms info)))
        (reverse forms)))))

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
           :content (reverse forms)})))))

(defn report [out-dir coverage]
  (doseq [{rel-file :file, content :content} (gather-stats coverage)]
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
  (copy (resource-reader "blanket.css") (File. out-dir "blanket.css"))
  (doseq [{rel-file :file, content :content} (gather-stats cov)]
    (let [file (File. out-dir (str rel-file ".html"))]
      (.mkdirs (.getParentFile file))
      (with-out-writer file
        (println "<html>")
        (println " <head>")
        (println "  <link rel=\"stylesheet\" href=\"../../blanket.css\"/>")
        (println "  <title>" rel-file "</title>")
        (println " </head>")
        (println " <body>")
        (doseq [info content]
          (let [cls (cond (:covered? info) "covered" 
                          (:blank?   info) "blank"
                          :else            "not-covered")]
            (printf "<span class=\"%s\">%s</span><br/>%n" cls (replace-spaces (:text info)))))
        (println " </body>")
        (println "</html>")))))



(defn -main [& args]
  (doseq [file (file-seq ".")]
    (println "File is" file)))

(meta (find-ns 'com.mdelaurentis.blanket))



