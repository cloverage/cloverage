(ns leiningen.blanket
  (:use [com.mdelaurentis coverage]
        [leiningen.compile :only [eval-in-project]]))

(defn blanket
  "Produce a test coverage report."
  [project file]
  (eval-in-project 
   project
   (println "Producing coverage report for" file)
   (analyze file)))