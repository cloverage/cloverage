(ns cloverage.report.coveralls
  (:require
   [clojure.string :as s]
   [clojure.java.io :as io]
   [clojure.data.json :as json]
   [cloverage.report :refer [line-stats with-out-writer]])
  (:import
   [java.security MessageDigest]))

(defn md5 [^String s]
  (let [algorithm (MessageDigest/getInstance "MD5")
        size (* 2 (.getDigestLength algorithm))
        raw (.digest algorithm (.getBytes s))
        sig (.toString (BigInteger. 1 raw) 16)
        padding (apply str (repeat (- size (count sig)) "0"))]
    (str padding sig)))

(defn- env?
  ([s] (env? s "true"))
  ([s value] (= (System/getenv s) value)))

(defn- service-info [sname job-id-var]
  [sname (System/getenv job-id-var)])

(defn- file-coverage [[file file-forms]]
  (let [lines (line-stats file-forms)]
    {:name file
     :source_digest (md5 (s/join "\n" (map :text lines)))
     :coverage (map #(if (:instrumented? %) (:hit %)) lines)}))

(defn report [^String out-dir forms]
  (let [output-file (io/file out-dir "coveralls.json")
        [service job-id]
        (cond
          ;; docs.travis-ci.com/user/ci-environment/
          (env? "TRAVIS")
          (service-info "travis-ci" "TRAVIS_JOB_ID")

          ;; circleci.com/docs/environment-variables
          (env? "CIRCLECI")
          (service-info "circleci" "CIRCLE_BUILD_NUM")

          ;; bit.ly/semaphoreapp-env-vars
          (env? "SEMAPHORE")
          (service-info "semaphore" "REVISION")

          ;; bit.ly/jenkins-env-vars
          (System/getenv "JENKINS_URL")
          (service-info "jenkins" "BUILD_ID")

          ;; bit.ly/codeship-env-vars
          (= (System/getenv "CI_NAME") "codeship")
          (service-info "codeship" "CI_BUILD_NUMBER"))

        covdata (->>
                 forms
                 (group-by :file)
                 (filter first)
                 (map file-coverage))]

    (println "Writing coveralls.io report to:" (.getAbsolutePath output-file))
    (.mkdirs (.getParentFile output-file))
    (with-out-writer output-file
      (json/pprint {:service_job_id job-id
                    :service_name service
                    :source_files covdata}
                   :escape-slash false))))

