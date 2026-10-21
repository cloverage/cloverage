(ns publish-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing run-tests]]
            [deps-deploy.deps-deploy :as deploy]
            [publish :as publish])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn temp-dir []
  (.toFile (Files/createTempDirectory "cloverage-publisher-" (make-array FileAttribute 0))))

(defn fixture [root]
  (let [version "1.3.0-SNAPSHOT"
        artifacts (mapv
                   (fn [name]
                     (let [base (str name "/" name "/" version "/" name "-" version)
                           pom (io/file root (str base ".pom"))
                           jar (io/file root (str base ".jar"))]
                       (io/make-parents pom)
                       (spit pom (str "<project><modelVersion>4.0.0</modelVersion><groupId>" name
                                      "</groupId><artifactId>" name "</artifactId><version>" version
                                      "</version></project>"))
                       (spit jar "test artifact")
                       {:name name
                        :files (mapv (fn [file suffix]
                                       {:path (str base suffix) :sha256 (publish/sha256 file)})
                                     [pom jar] [".pom" ".jar"])}))
                   ["cloverage" "lein-cloverage"])
        manifest {:version version :commit "tested-commit" :artifacts artifacts}]
    (spit (io/file root "manifest.edn") (pr-str manifest))
    manifest))

(defn with-fixture [f]
  (let [root (temp-dir)]
    (try (f root (fixture root))
         (finally (doseq [file (reverse (file-seq root))] (io/delete-file file))))))

(deftest package-validation
  (with-fixture
    (fn [root manifest]
      (is (= ["cloverage" "lein-cloverage"]
             (mapv :name (:artifacts (publish/check-packages root "tested-commit")))))
      (is (thrown-with-msg? Exception #"workflow commit"
                            (publish/check-packages root "other-commit")))
      (testing "a changed artifact cannot be uploaded"
        (spit (io/file root (get-in manifest [:artifacts 0 :files 1 :path])) "changed")
        (is (thrown-with-msg? Exception #"checksum" (publish/check-packages root nil)))))))

(deftest release-and-coordinate-validation
  (with-fixture
    (fn [root manifest]
      (spit (io/file root "manifest.edn") (pr-str (assoc manifest :version "1.3.0")))
      (is (thrown-with-msg? Exception #"Only SNAPSHOT" (publish/check-packages root nil)))
      (let [pom (io/file root (get-in manifest [:artifacts 0 :files 0 :path]))]
        (spit pom "<project><modelVersion>4.0.0</modelVersion><groupId>other</groupId><artifactId>cloverage</artifactId><version>1.3.0-SNAPSHOT</version></project>")
        (spit (io/file root "manifest.edn")
              (pr-str (assoc-in manifest [:artifacts 0 :files 0 :sha256] (publish/sha256 pom))))
        (is (thrown-with-msg? Exception #"POM coordinates" (publish/check-packages root nil)))))))

(deftest deployment-order-and-credentials
  (with-fixture
    (fn [root _]
      (let [manifest (publish/check-packages root nil)
            calls (atom [])
            tokens {"cloverage" "library-token" "lein-cloverage" "plugin-token"}]
        (with-redefs [deploy/deploy #(swap! calls conj %)]
          (is (thrown? Exception (publish/publish! manifest "test-user" (dissoc tokens "lein-cloverage"))))
          (is (empty? @calls))
          (publish/publish! manifest "test-user" tokens)
          (is (= ["library-token" "plugin-token"]
                 (mapv #(get-in % [:repository "clojars" :password]) @calls)))
          (is (= (mapv :jar (:artifacts manifest)) (mapv :artifact @calls))))
        (reset! calls [])
        (with-redefs [deploy/deploy (fn [opts]
                                      (swap! calls conj opts)
                                      (throw (ex-info "Deployment failed" {})))]
          (is (thrown? Exception (publish/publish! manifest "test-user" tokens)))
          (is (= 1 (count @calls))))))))

(deftest deploy-prepared-artifacts-to-local-maven-repository
  (with-fixture
    (fn [root _]
      (let [destination (temp-dir)
            manifest (publish/check-packages root nil)]
        (try
          ;; Exercise the real Maven transport, with no external writes or tokens.
          (doseq [{:keys [pom jar]} (:artifacts manifest)]
            (deploy/deploy {:installer :remote :artifact jar :pom-file pom
                            :sign-releases? false
                            :repository {"test" {:url (str (.toURI destination))}}}))
          (doseq [name ["cloverage" "lein-cloverage"]]
            (let [files (file-seq (io/file destination name name "1.3.0-SNAPSHOT"))
                  jar (first (filter #(.endsWith (.getName %) ".jar") files))
                  source (io/file root name name "1.3.0-SNAPSHOT" (str name "-1.3.0-SNAPSHOT.jar"))]
              (is (some? jar))
              (is (= (publish/sha256 source) (publish/sha256 jar)))))
          (finally (doseq [file (reverse (file-seq destination))] (io/delete-file file))))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'publish-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ fail error)) 0 1))))
