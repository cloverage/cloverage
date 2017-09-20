(ns leiningen.cloverage
  (:require [leiningen.run :as run]
            [bultitude.core :as blt]))

(defn ns-names-for-dirs [dirs]
  (map name (mapcat blt/namespaces-in-dir dirs)))

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
  (let [source-namespaces (ns-names-for-dirs (:source-paths project))
        test-namespace    (ns-names-for-dirs (:test-paths project))
        project (if (already-has-cloverage? project)
                  project
                  (update-in project [:dependencies]
                             conj    ['cloverage (get-lib-version)]))]
    (apply run/run project
           "-m" "cloverage.coverage"
           (concat (mapcat  #(list "-x" %) test-namespace)
                   args
                   source-namespaces))))
