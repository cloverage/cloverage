(defproject cloverage/boot-cloverage "1.0.0-SNAPSHOT"
  :description "Boot plugin for cloverage"
  :url "https://github.com/lshift/cloverage"
  :scm {:name "git"
        :dir  ".."
        :url  "https://www.github.com/lshift/cloverage"
        :tag  "HEAD"}
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :plugins [[lein-release "1.0.6"]]
  :boot-release {
    :scm :git ; Because we're not in the top-level directory, so it doesn't auto-detect
    :deploy-via :clojars
  }
  :deploy-repositories [["clojars" {:username :env/clojars_username :password :env/clojars_password :sign-releases false}]]
  :min-lein-version "2.0.0"
  :eval-in-leiningen true)
