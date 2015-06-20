(defproject cloverage "1.0.7-SNAPSHOT"
  :description "Form-level test coverage for clojure."
  :url "https://www.github.com/lshift/cloverage"
  :scm {:name "git"
        :dir  ".."
        :url  "https://www.github.com/lshift/cloverage"
        :tag  "HEAD"}
  :main ^:skip-aot cloverage.coverage
  :aot [clojure.tools.reader]
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :deploy-repositories [["clojars" {:username :env/clojars_username :password :env/clojars_password :sign-releases false}]]
  :plugins [[lein-release "1.0.6"]]
  :lein-release {
    :scm :git ; Because we're not in the top-level directory, so it doesn't auto-detect
    :deploy-via :clojars
  }
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.reader "0.9.2"]
                 [org.clojure/tools.cli "0.3.1"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.clojure/data.xml "0.0.8"]
                 [bultitude "0.2.7"]
                 [riddley "0.1.10"]
                 [slingshot "0.12.2"]
                 [cheshire "5.5.0"]])
