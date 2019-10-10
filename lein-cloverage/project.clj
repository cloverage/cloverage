(defproject lein-cloverage "1.1.3-SNAPSHOT"
  :description "Lein plugin for cloverage"
  :url "https://github.com/cloverage/cloverage"
  :scm {:name "git"
        :dir ".."
        :url "https://www.github.com/cloverage/cloverage"
        :tag "HEAD"}
  :vcs :git
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :deploy-repositories {"releases"
                        {:url "https://repo.clojars.org"
                         :creds :gpg}}
  :lein-release {:scm :git ; Because we're not in the top-level directory, so it doesn't auto-detect
                 :deploy-via :clojars}
  :release-tasks [["vcs" "assert-committed"]
                  ["change" "version" "leiningen.release/bump-version" "release"]
                  ["vcs" "commit"]
                  ;; We can't tag here: lein's builtin release functionality
                  ;; tags only by version, but we share a repository with
                  ;; cloverage itself. It's up to the maintainers to make this
                  ;; not desync too much.
                  #_["vcs" "tag"]
                  ["deploy"]
                  ["change" "version" "leiningen.release/bump-version"]
                  ["vcs" "commit"]
                  ["vcs" "push"]]

  :min-lein-version "2.0.0"
  :profiles {:dev {:plugins [[lein-cljfmt "0.5.7"]
                             [jonase/eastwood "0.2.5"]
                             [lein-kibit "0.1.6"]]}}
  :eval-in-leiningen true)
