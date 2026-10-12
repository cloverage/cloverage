(defproject cloverage-integration "0.0.0"
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [cloverage/cloverage "1.3.0-SNAPSHOT"]]
  :plugins [[lein-cloverage "1.3.0-SNAPSHOT"]]
  :profiles {:auto-version {:dependencies ^:replace [[org.clojure/clojure "1.12.0"]]}})
