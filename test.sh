#!/usr/bin/env bash
set -e

rm -rf src/test/scala/codegentest

scala-cli --power package \
  --native \
  --native-mode debug src/main/scala \
  -o .bin/codegen -f

./.bin/codegen \
  -host=localhost \
  -user=postgres \
  -database=postgres \
  -operate-database=postgres \
  -port=5432 \
  -password=postgres \
  -use-docker-image="postgres:17-alpine" \
  -output-dir=src/test/scala \
  -pkg-name=codegentest.generated \
  -exclude-tables=unsupported_yet,flyway_schema_history \
  -source-dir=src/test/resources/db/migration

scala-cli run src/test/scala/GeneratedCodeTest.scala