(defproject cloverage "1.2.0-SNAPSHOT"
  :description "Form-level test coverage for clojure."
  :url "https://www.github.com/cloverage/cloverage"
  :scm {:name "git"
        :dir ".."
        :url "https://www.github.com/cloverage/cloverage"
        :tag "HEAD"}
  :vcs :git
  :main ^:skip-aot cloverage.coverage
  :license {:name "Eclipse Public License - v 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"
            :distribution :repo
            :comments "same as Clojure"}
  :deploy-repositories [["releases" :clojars]
                        ["snapshots" :clojars]]
  :dependencies [[org.clojure/tools.reader "1.3.2"]
                 [org.clojure/tools.cli "0.4.2"]
                 [org.clojure/tools.logging "0.5.0"]
                 [org.clojure/tools.namespace "0.3.1"]
                 [org.clojure/java.classpath "0.3.0"]
                 [org.clojure/data.xml "0.0.8"]
                 [org.clojure/data.json "0.2.6"]
                 [riddley "0.2.0"]
                 [slingshot "0.12.2"]]
  :profiles {:dev {:aot ^:replace []
                   :dependencies [[org.clojure/clojure "1.10.1"]
                                  [pjstadig/humane-test-output "0.10.0"]]
                   :injections [(require 'pjstadig.humane-test-output)
                                (pjstadig.humane-test-output/activate!)]
                   :plugins [[lein-cljfmt "0.6.4"]
                             [jonase/eastwood "0.3.6"]
                             [lein-kibit "0.1.7"]]
                   :eastwood {:exclude-linters [:no-ns-form-found]}
                   :global-vars {*warn-on-reflection* true}}
             :1.4 {:dependencies [[org.clojure/clojure "1.4.0"]]}
             :1.5 {:dependencies [[org.clojure/clojure "1.5.1"]]}
             :1.6 {:dependencies [[org.clojure/clojure "1.6.0"]]}
             :1.7 {:dependencies [[org.clojure/clojure "1.7.0"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :1.10 {:dependencies [[org.clojure/clojure "1.10.1"]]}
             :test {:source-paths ["sample"]
                    :jvm-opts ["-Duser.language=en-US"]}}
  :aliases {"all" ["with-profile" "+1.4:+1.5:+1.6:+1.7:+1.8:+1.9:+1.10"]})
