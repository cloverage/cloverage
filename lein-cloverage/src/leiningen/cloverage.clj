(ns leiningen.cloverage
  (:require [leiningen.run :as run]
            [bultitude.core :as blt]))

(defn ns-names-for-dirs [dirs]
  (map name (mapcat blt/namespaces-in-dir dirs)))

(defn cloverage
  "Run code coverage on the project.

  Specify -o OUTPUTDIR for output, for other options see cloverage."
  [project & args]
  (let [source-namespaces (ns-names-for-dirs (:source-paths project))
        test-namespace    (ns-names-for-dirs (:test-paths project))]
    (apply run/run (update-in project [:dependencies]
                              conj    ['cloverage "1.0.0-SNAPSHOT"])
           "-m" "cloverage.coverage"
           (concat (mapcat  #(list "-x" %) test-namespace)
                   args
                   source-namespaces))))
