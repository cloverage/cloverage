(ns leiningen.cloverage
  (:require [leiningen.run :as run]))

(defn get-lib-version []
  (or (System/getenv "CLOVERAGE_VERSION") "RELEASE"))

(defn coll-to-args [flag coll]
  (mapcat #(list flag %) coll))

(defn cloverage
  "Run code coverage on the project.

  To specify cloverage version, set the CLOVERAGE_VERSION environment variable.
  Specify -o OUTPUTDIR for output directory, for other options see cloverage."
  [project & args]
  (let [source-paths (coll-to-args "-p" (:source-paths project))
        test-paths   (coll-to-args "-s" (:test-paths project))]
    (apply run/run (update-in project [:dependencies]
                              conj    ['cloverage (get-lib-version)])
           "-m" "cloverage.coverage"
           (concat source-paths test-paths args))))
