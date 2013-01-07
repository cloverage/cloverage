(ns leiningen.cloverage
  (:require [leiningen.run :as run]))

(defn cloverage
  "I don't do a lot."
  [project & args]
  (apply run/run (update-in project [:dependencies]
                            conj    ['cloverage "1.0.0-SNAPSHOT"])
         "-m" "cloverage.coverage" args))
