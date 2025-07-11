#!/usr/bin/env bash
set -e

# generate binary
scala-cli --power package \
  --native \
  --native-mode release-fast PgCodeGen.scala \
  -o out/codegen -f

echo "⏳Test generated code"
./out/codegen \
  -use-docker-image=postgres:17-alpine \
  -output-dir=test-generated \
  -pkg-name=generated \
  -exclude-tables=unsupported_yet \
  -source-dir=test/migrations \
  -force=true

TIMESTAMP_A=$(stat test-generated | grep Modify)

# run test for generated code
scala-cli run PgCodeGenTest.scala
echo "✅ Test of generated code successful"

echo "⏳running generator again with -force=true should re-run code generation"
./out/codegen \
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
./out/codegen \
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

echo "⏳ running code generator with provided connection"
docker run --rm --name codegentest -e POSTGRES_PASSWORD=postgres -p 5555:5432 -d postgres:17-alpine

./out/codegen \
  -use-docker-image="postgres:17-alpine" \
  -output-dir=test-generated \
  -pkg-name=generated \
  -exclude-tables=unsupported_yet \
  -source-dir=test/migrations \
  -use-connection=postgresql://postgres:postgres@localhost:5555/postgres \
  -force=true && echo "✅ Code generation for provided connection ok."

echo "⏳ Running code generator with custom migrations command"
./out/codegen \
  -use-docker-image="postgres:17-alpine" \
  -output-dir=test-generated \
  -pkg-name=generated \
  -exclude-tables=unsupported_yet \
  -source-dir=test/migrations \
  -migration-command="./test/dumbo -user=%user -password=%password -url=postgresql://%host:%port/%database -location=%sourcePath migrate" \
  -debug=1 \
  -use-connection=postgresql://postgres:postgres@localhost:5555/postgres \
  -force=true && echo "✅ Code generation with custom migration command ok."

docker rm -f codegentest
