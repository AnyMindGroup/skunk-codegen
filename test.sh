#!/usr/bin/env bash
set -e

# pick scala runner (prefer scala-cli, fallback to scala); fail if none available
if command -v scala-cli >/dev/null 2>&1; then
  SCALA_CMD=scala-cli
elif command -v scala >/dev/null 2>&1; then
  SCALA_CMD=scala
else
  echo "❌ Neither scala-cli nor scala found on PATH" >&2
  exit 1
fi

# generate binary
CODEGEN_BIN=out/skunk-codegen-$(uname -m)-$(uname | tr '[:upper:]' '[:lower:]')
$SCALA_CMD --power package \
  --native \
  --native-mode release-fast PgCodeGen.scala -source:future -Werror \
  -o $CODEGEN_BIN -f

echo "⏳Test generated code"
$CODEGEN_BIN \
  -use-docker-image=postgres:17-alpine \
  -output-dir=test-generated \
  -pkg-name=generated \
  -exclude-tables=unsupported_yet \
  -source-dir=test/migrations \
  -force=true

TIMESTAMP_A=$(stat test-generated | grep Modify)

# run test for generated code
$SCALA_CMD run PgCodeGenTest.scala -source:future -Werror
echo "✅ Test of generated code successful"

echo "⏳running generator again with -force=true should re-run code generation"
./$CODEGEN_BIN \
  -use-docker-image="postgres:17-alpine" \
  -output-dir=test-generated \
  -pkg-name=generated \
  -exclude-tables=unsupported_yet \
  -source-dir=test/migrations \
  -force=true

TIMESTAMP_B=$(stat test-generated | grep Modify)

if [ "$TIMESTAMP_A" != "$TIMESTAMP_B" ]; then
  echo "✅ Code generation with -force=true as expected (timestamps differ)"
else
  echo "❌ Error: Code generation did not re-run (timestamps are the same)"
  exit 1
fi

echo "⏳ running generator again with -force=false should not run code generation"
./$CODEGEN_BIN \
  -use-docker-image="postgres:17-alpine" \
  -output-dir=test-generated \
  -pkg-name=generated \
  -exclude-tables=unsupported_yet \
  -source-dir=test/migrations \
  -force=false

TIMESTAMP_C=$(stat test-generated | grep Modify)

if [ "$TIMESTAMP_B" == "$TIMESTAMP_C" ]; then
  echo "✅ Code generation with -force=false as expected (timestamps are the same)"
else
  echo "❌ Error: Code generation -force=false not as expected (timestamps differ)"
  exit 1
fi

echo "⏳ running generator again with additional non-sql file and -force=false should not run code generation"
./$CODEGEN_BIN \
  -use-docker-image="postgres:17-alpine" \
  -output-dir=test-generated \
  -pkg-name=generated \
  -exclude-tables=unsupported_yet \
  -source-dir=test/migrations_copy \
  -force=false

TIMESTAMP_D=$(stat test-generated | grep Modify)

if [ "$TIMESTAMP_B" == "$TIMESTAMP_D" ]; then
  echo "✅ Code generation with additional non-sql file and -force=false as expected (timestamps are the same)"
else
  echo "❌ Error: Code generation with additional non-sql file and -force=false not as expected (timestamps differ)"
  exit 1
fi

echo "⏳ running code generator with provided connection"
docker run --rm --name codegentest -e POSTGRES_PASSWORD=postgres -p 5555:5432 -d postgres:17-alpine

(./$CODEGEN_BIN \
  -use-docker-image="postgres:17-alpine" \
  -output-dir=test-generated \
  -pkg-name=generated \
  -exclude-tables=unsupported_yet \
  -source-dir=test/migrations \
  -use-connection=postgresql://postgres:postgres@localhost:5555/postgres \
  -force=true && echo "✅ Code generation for provided connection ok.") || (
  docker rm -f codegentest
  exit 1
)

docker rm -f codegentest

echo "⏳Process should fail on running invalid sql"
if $CODEGEN_BIN \
  -use-docker-image=postgres:17-alpine \
  -output-dir=test-generated \
  -pkg-name=generated \
  -exclude-tables=unsupported_yet \
  -source-dir=test/migrations_invalid \
  -force=true; then
  echo "❌ Process did not fail as expected"
  exit 1
else
  echo "✅ Process failed as expected"
fi
