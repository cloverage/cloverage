;; this file sets profiles for use under Travis CI, so that Travis can deploy master build snapshots
{:deploy-repositories [["clojars" {:username :env/clojars_username :password :env/clojars_password :sign-releases false}]]}
