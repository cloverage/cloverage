#!/bin/bash
if [ x${TRAVIS_PULL_REQUEST} != xfalse ]; then
  echo "Building a pull request, so not deploying"
elif [ x${TRAVIS_BRANCH} != xmaster ]; then
  echo "On branch '$TRAVIS_BRANCH' which isn't master so not doing a deploy"
else
  echo "Not a pull request, so deploying"
  (cd cloverage && lein deploy clojars)
  (cd lein-cloverage && lein deploy clojars)
fi