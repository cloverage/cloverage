#!/bin/bash
if [ x${TRAVIS_PULL_REQUEST} == xfalse ]; then
  echo "Not a pull request, so deploying"
  (cd cloverage && lein deploy clojars)
  (cd lein-cloverage && lein deploy clojars)
else
  echo "Building a pull request, so not deploying"
fi