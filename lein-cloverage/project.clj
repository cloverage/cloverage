(defproject lein-cloverage "1.3.0-SNAPSHOT"
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
  :dependencies [[org.clojure/clojure "1.11.1" :scope "provided"]]
  :deploy-repositories [["releases" :clojars]
                        ["snapshots" :clojars]]
  :lein-release {:scm :git ; Because we're not in the top-level directory, so it doesn't auto-detect
                 :deploy-via :clojars}
  :aliases {"test-ci" ["with-profile" "-dev,+test,+ci" "test"]
            "eastwood-ci" ["with-profile" "-dev,+test,+ci" "eastwood"]}
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
  :profiles {:dev {:plugins [[lein-cljfmt "0.6.4"]
                             [jonase/eastwood "1.2.3"]
                             [lein-kibit "0.1.7"]]}
             :ci {:pedantic? :abort}}
  :eval-in-leiningen true)
