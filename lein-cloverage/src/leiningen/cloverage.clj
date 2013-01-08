(ns leiningen.cloverage
  (:require [leiningen.run :as run]
            [bultitude.core :as blt]))

(defn cloverage
  "Run code coverage on the project.

  Specify -o OUTPUTDIR for output, for other options see cloverage."
  [project & args]
  (println "START")
  (let [source-namespaces (mapcat blt/namespaces-in-dir (:source-paths project))
        test-namespace    (mapcat blt/namespaces-in-dir (:test-paths project))]
    (println run/run (update-in project [:dependencies]
                                conj    ['cloverage "1.0.0-SNAPSHOT"])
           "-m" "cloverage.coverage"
           (concat (mapcat  #(list "-x" %) test-namespace)
                   args
                   source-namespaces))))
