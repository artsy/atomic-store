#!/usr/bin/env bash

set -e

if [ "$TRAVIS_BRANCH" = 'master' ] && [ "$TRAVIS_PULL_REQUEST" == 'false' ]; then
  openssl aes-256-cbc -K $encrypted_c25aa352ba20_key -iv $encrypted_c25aa352ba20_iv
  -in codesigning.asc.enc -out ci/codesigning.asc -d
  gpg --fast-import ci/codesigning.asc
fi
