(defproject lein-cloverage "1.0.10-SNAPSHOT"
  :description "Lein plugin for cloverage"
  :url "https://github.com/cloverage/cloverage"
  :scm {:name "git"
        :dir  ".."
        :url  "https://www.github.com/cloverage/cloverage"
        :tag  "HEAD"}
  :vcs :git
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :plugins [[lein-release "1.0.9"]]
  :lein-release {:scm :git ; Because we're not in the top-level directory, so it doesn't auto-detect
                 :deploy-via :clojars}
  :deploy-repositories [["clojars" {:username :env/clojars_username :password :env/clojars_password :sign-releases false}]]
  :min-lein-version "2.0.0"
  :dependencies [[bultitude "0.2.8"]]
  :profiles {:dev {:plugins [[lein-cljfmt "0.5.6"]]}}
  :eval-in-leiningen true)
