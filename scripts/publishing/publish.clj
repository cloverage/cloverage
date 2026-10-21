(ns publish
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [deps-deploy.deps-deploy :as deploy])
  (:import [java.security MessageDigest]))

(def token-names
  {"cloverage" "CLOJARS_CLOVERAGE_TOKEN"
   "lein-cloverage" "CLOJARS_LEIN_CLOVERAGE_TOKEN"})

(defn sha256 [file]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (with-open [stream (io/input-stream file)]
      (let [buffer (byte-array 8192)]
        (loop []
          (let [n (.read stream buffer)]
            (when (pos? n)
              (.update digest buffer 0 n)
              (recur))))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest)))))

(defn check-packages [root commit]
  (let [{:keys [version artifacts] :as manifest}
        (edn/read-string (slurp (io/file root "manifest.edn")))
        root-path (.toPath (.getCanonicalFile (io/file root)))]
    (when-not (and (string? version) (str/ends-with? version "-SNAPSHOT"))
      (throw (ex-info "Only SNAPSHOT versions may be published" {})))
    (when (and commit (not= commit (:commit manifest)))
      (throw (ex-info "Packages do not match the workflow commit" {})))
    (when-not (= ["cloverage" "lein-cloverage"] (mapv :name artifacts))
      (throw (ex-info "Expected the library followed by the plugin" {})))
    (assoc manifest :artifacts
           (mapv
            (fn [{:keys [name files] :as artifact}]
              (let [paths (mapv
                           (fn [{:keys [path sha256] :as entry}]
                             (let [file (.getCanonicalFile (io/file root path))
                                   expected (.resolve root-path (str name "/" name "/" version))]
                               (when-not (.startsWith (.toPath file) root-path)
                                 (throw (ex-info "Package is outside the prepared repository" {})))
                               (when-not (= sha256 (publish/sha256 file))
                                 (throw (ex-info "Package checksum mismatch" {:path path})))
                               ;; Only the versioned JAR and POM are handed to deps-deploy.
                               (when (or (str/ends-with? path ".jar") (str/ends-with? path ".pom"))
                                 (when-not (= expected (.getParent (.toPath file)))
                                   (throw (ex-info "Artifact is outside its snapshot coordinates" {}))))
                               (assoc entry :file file)))
                           files)
                    one-file (fn [suffix]
                               (let [matches (filter #(str/ends-with? (:path %) suffix) paths)]
                                 (when-not (= 1 (count matches))
                                   (throw (ex-info "Expected one JAR and POM per artifact" {:name name})))
                                 (str (:file (first matches)))))
                    pom (one-file ".pom")
                    jar (one-file ".jar")]
                (when-not (= {:group-id name :artifact-id name :version version}
                             (deploy/coordinates-from-pom pom))
                  (throw (ex-info "POM coordinates do not match the manifest" {:name name})))
                (assoc artifact :pom pom :jar jar)))
            artifacts))))

(defn publish! [manifest username tokens]
  ;; Check both credentials before publishing either artifact.
  (when-not (and (not (str/blank? username))
                 (every? #(not (str/blank? (get tokens %))) (keys token-names)))
    (throw (ex-info "Set CLOJARS_USERNAME and both artifact-scoped deploy tokens" {})))
  (doseq [{:keys [name pom jar]} (:artifacts manifest)]
    (deploy/deploy {:installer :remote
                    :artifact jar
                    :pom-file pom
                    :sign-releases? false
                    :repository {"clojars" {:url "https://repo.clojars.org"
                                            :username username
                                            :password (get tokens name)}}})))

(defn -main [root & [mode]]
  (when-not (and root (#{nil "--check"} mode))
    (throw (ex-info "Usage: clojure -M -m publish REPOSITORY [--check]" {})))
  (let [manifest (check-packages root (System/getenv "GITHUB_SHA"))]
    (if (= "--check" mode)
      (println "Validated both artifacts at" (:version manifest))
      (do
        (publish! manifest (System/getenv "CLOJARS_USERNAME")
                  (into {} (map (fn [[name variable]] [name (System/getenv variable)]) token-names)))
        (when-let [summary (System/getenv "GITHUB_STEP_SUMMARY")]
          (spit summary (str "Published both artifacts at `" (:version manifest)
                             "` from `" (:commit manifest) "`.\n") :append true))))))
