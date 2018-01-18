(ns leiningen.cloverage
  (:require [leiningen.run :as run]))

(defn get-lib-version []
  (or (System/getenv "CLOVERAGE_VERSION") "RELEASE"))

(defn already-has-cloverage? [project]
  (seq (for [[id _version] (:dependencies project)
             :when (= id 'cloverage/cloverage)]
         true)))

(defn ^:pass-through-help cloverage
  "Run code coverage on the project.

  You can set the CLOVERAGE_VERSION environment variable to override what
  version of cloverage is used, but it's better to set it in :dependencies.

  Specify -o OUTPUTDIR for output directory, for other options run
  `lein cloverage --help`."
  [project & args]
  (let [project (if (already-has-cloverage? project)
                  project
                  (update-in project [:dependencies]
                             conj    ['cloverage (get-lib-version)]))]
    (apply run/run project
           "-m" "cloverage.coverage"
           (concat (mapcat #(list "-p" %) (:source-paths project))
                   (mapcat #(list "-s" %) (:test-paths project))
                   args))))
