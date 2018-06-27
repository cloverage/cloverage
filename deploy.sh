#!/bin/bash
set -euxo pipefail
if [ "x${TRAVIS_PULL_REQUEST}" != xfalse ]; then
  echo "Building a pull request, so not deploying"
elif [ "x${TRAVIS_BRANCH}" != xmaster ]; then
  echo "On branch '$TRAVIS_BRANCH' which isn't master so not doing a deploy"
else
  echo "Not a pull request, so deploying"
  cp .travis.profiles.clj cloverage/profiles.clj
  (cd cloverage && lein deploy clojars)
  cp .travis.profiles.clj lein-cloverage/profiles.clj
  (cd lein-cloverage && lein deploy clojars)
fi
