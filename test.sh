#!/usr/bin/env bash
set -e

rm -rf test-generated

scala-cli --power package \
  --native \
  --native-mode release-fast PgCodeGen.scala \
  -o .bin/codegen -f

./.bin/codegen \
  -use-docker-image="postgres:17-alpine" \
  -output-dir=test-generated \
  -pkg-name=generated \
  -exclude-tables=unsupported_yet \
  -source-dir=test-migrations

scala-cli run PgCodeGenTest.scala