#!/usr/bin/env bash

set -e

openssl enc -in codesigning.asc.enc -out codesigning.asc -d -aes256 -k $ENCRYPTION_KEY

gpg --fast-import codesigning.asc

# sbt-sonatype looks in wrong place sometimes for credentials
mkdir -p ~/.sbt/0.13
cp project/sonatype.sbt ~/.sbt/0.13/
