(defproject cloverage "1.0.5"
  :description "Form-level test coverage for clojure."
  :url "https://www.github.com/lshift/cloverage"
  :scm {:name "git"
        :dir  ".."
        :url  "https://www.github.com/lshift/cloverage"
        :tag  "HEAD"}
  :main ^:skip-aot cloverage.coverage
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
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.cli "0.2.2"]
                 [org.clojure/tools.logging "0.2.3"]
                 [org.clojure/data.xml "0.0.7"]
                 [bultitude "0.2.0"]
                 [riddley "0.1.4"]
                 [slingshot "0.10.3"]
                 [cheshire "5.3.1"]])
