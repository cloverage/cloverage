#!/usr/bin/env bb
(require '[babashka.fs :as fs]
         '[babashka.process :as process]
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

(let [dry-run? (case (vec *command-line-args*)
                 [] false
                 ["--dry-run"] true
                 (throw (ex-info "Usage: bb scripts/publish-snapshots.clj [--dry-run]" {})))
      modules ["cloverage" "lein-cloverage"]
      versions (mapv project-version modules)]
  ;; Validate both artifacts before publishing either of them.
  (when-not (every? #(and (string? %) (str/ends-with? % "-SNAPSHOT")) versions)
    (throw (ex-info "Only SNAPSHOT versions may be published" {:versions versions})))
  (when-not (apply = versions)
    (throw (ex-info "Library and plugin versions must match" {:versions versions})))
  (when (and (not dry-run?)
             (some #(str/blank? (System/getenv %)) ["CLOJARS_USERNAME" "CLOJARS_PASSWORD"]))
    (throw (ex-info "Set CLOJARS_USERNAME and CLOJARS_PASSWORD to publish snapshots" {})))
  (let [local-repo (when dry-run? (fs/create-temp-dir {:prefix "cloverage-snapshots-"}))]
    (doseq [module modules]
      (println (if dry-run? "Checking snapshot deployment for" "Publishing") module (first versions))
      (let [command (if dry-run?
                      ["lein" "with-profile" "-dev" "update-in" ":deploy-repositories" "conj"
                       (pr-str ["snapshot-check" {:url (str (.toUri local-repo)) :no-auth true}])
                       "--" "deploy" "snapshot-check"]
                      ["lein" "with-profile" "-dev" "deploy" "snapshots"])]
        (process/check @(process/process command
                                         {:dir (str (fs/path repo-root module)) :inherit true}))))
    (when dry-run?
      (println "Both snapshots deployed to local repository:" (str local-repo)))
    (when-let [summary (System/getenv "GITHUB_STEP_SUMMARY")]
      (spit summary
            (str (if dry-run? "Validated" "Published") " both artifacts at `" (first versions)
                 "` from commit `" (System/getenv "GITHUB_SHA") "`.\n")
            :append true))))
