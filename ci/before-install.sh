#!/usr/bin/env bash

set -e

openssl enc -in codesigning.asc.enc -out codesigning.asc -d -aes256 -k $ENCRYPTION_KEY

gpg --fast-import codesigning.asc
