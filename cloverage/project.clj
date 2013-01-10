(defproject cloverage "1.0.0-SNAPSHOT"
  :description "Form-level test coverage for clojure."
  :url "https://www.github.com/lshift/cloverage"
  :main cloverage.coverage
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/tools.cli "0.2.2"]
                 [org.clojure/tools.logging "0.2.3"]
                 [bultitude "0.2.0"]
                 [slingshot "0.10.3"]])
