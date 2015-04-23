#!/bin/bash
if [ x${TRAVIS_BRANCH} == x${DEPLOY_BRANCH} ]; then
  echo "On branch '$TRAVIS_BRANCH' which is the deployment branch so deploying"
  (cd cloverage && lein deploy clojars)
  (cd lein-cloverage && lein deploy clojars)
else
  echo "On branch '$TRAVIS_BRANCH' which isn't the deployment branch ($DEPLOY_BRANCH) so not doing a deploy"
fi