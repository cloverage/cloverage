#!/usr/bin/env bb
(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def repo-root (fs/parent (fs/parent (fs/absolutize *file*))))

(defn project-version [module]
  (with-open [reader (java.io.PushbackReader.
                      (io/reader (str (fs/path repo-root module "project.clj"))))]
    (binding [*read-eval* false]
      (loop []
        (let [form (read {:eof nil} reader)]
          (cond
            (nil? form) (throw (ex-info "No defproject found" {:module module}))
            (and (seq? form) (= 'defproject (first form))) (nth form 2)
            :else (recur)))))))

(let [output (or (first *command-line-args*) "target/snapshots")
      local-repo (fs/absolutize output)
      modules ["cloverage" "lein-cloverage"]
      versions (mapv project-version modules)]
  ;; Validate both artifacts before packaging either of them.
  (when-not (every? #(and (string? %) (str/ends-with? % "-SNAPSHOT")) versions)
    (throw (ex-info "Only SNAPSHOT versions may be packaged" {:versions versions})))
  (when-not (apply = versions)
    (throw (ex-info "Library and plugin versions must match" {:versions versions})))
  (when (and (fs/exists? local-repo) (seq (fs/list-dir local-repo)))
    (throw (ex-info "Use an empty output directory for snapshot packages" {:output output})))
  (fs/create-dirs local-repo)
  (let [sha256 (fn [file]
                 (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
                   (.update digest (fs/read-all-bytes file))
                   (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest)))))
        artifacts (atom [])]
    (doseq [module modules]
      (println "Preparing" module (first versions))
      (let [command ["lein" "with-profile" "-dev" "update-in" ":deploy-repositories" "conj"
                     (pr-str ["snapshot-check" {:url (str (.toUri local-repo)) :no-auth true}])
                     "--" "deploy" "snapshot-check"]]
        (process/check @(process/process command
                                         {:dir (str (fs/path repo-root module)) :inherit true})))
      (swap! artifacts conj
             {:name module
              :files (->> (fs/glob (fs/path local-repo module module) "**")
                          (filter fs/regular-file?)
                          (sort)
                          (mapv (fn [file]
                                  {:path (str (fs/relativize local-repo file))
                                   :sha256 (sha256 file)})))}))
    (spit (str (fs/path local-repo "manifest.json"))
          (json/generate-string {:version (first versions)
                                 :commit (or (System/getenv "GITHUB_SHA")
                                             (str/trim (:out (process/shell {:dir (str repo-root) :out :string}
                                                                            "git" "rev-parse" "HEAD"))))
                                 :artifacts @artifacts}
                                {:pretty true}))
    (println "Prepared snapshot repository:" (str local-repo))
    (when-let [summary (System/getenv "GITHUB_STEP_SUMMARY")]
      (spit summary
            (str "Prepared both artifacts at `" (first versions)
                 "` from commit `" (System/getenv "GITHUB_SHA") "`.\n")
            :append true))))
