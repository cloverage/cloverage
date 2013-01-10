(defproject lein-cloverage "1.0.1"
  :description "Lein plugin for cloverage"
  :url "https://github.com/lshift/cloverage"
  :scm {:name "git"
        :dir  ".."
        :url  "https://www.github.com/lshift/cloverage"
        :tag  "HEAD"}
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :min-lein-version "2.0.0"
  :dependencies [[cloverage "1.0.1"]
                 [bultitude "0.2.0"]]
  :eval-in-leiningen true)
