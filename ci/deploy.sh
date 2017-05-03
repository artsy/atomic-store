#!/usr/bin/env bash

set -e

if [ "$TRAVIS_BRANCH" = 'master' ] && [ "$TRAVIS_PULL_REQUEST" == 'false' ] && [ "$TRAVIS_SCALA_VERSION" == "2.11.11" ]; then
  sbt publishSigned
  sbt sonatypeRelease
fi
