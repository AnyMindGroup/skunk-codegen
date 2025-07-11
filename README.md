# skunk-codegen

`skunk-codegen` is a Scala 3 code generator for PostgreSQL database schemas. It introspects your database and generates type-safe Scala code for use with the [skunk](https://tpolecat.github.io/skunk/) functional Postgres library.

## Features

- **Schema Introspection:** Reads tables, columns, constraints (primary, unique, foreign keys), indexes, and enums from a PostgreSQL database.
- **Code Generation:** Produces Scala case classes, codecs, and table definitions for each table and enum in your schema.
- **Migration Support:** Runs database migrations using a Dockerized migration tool before code generation.
- **Docker Integration:** Can spin up a PostgreSQL Docker container for isolated code generation.
- **Customizable:** Supports excluding tables, specifying output/source directories, and customizing package names.

## Usage

Run the generator via command line:

```shell
./path/to/codegen_x86_64 \
  -use-docker-image="postgres:17-alpine" \
  -output-dir=my/out/dir \
  -pkg-name=my.package \
  -exclude-tables=table_name_a,table_name_b \
  -source-dir=path/to/db/migrations
```

Example usage of command line in sbt:

```scala
lazy val myProject = (project in file("."))
  .settings(
    Compile / sourceGenerators += skunkCodeGenTask(
      pkgName = "my.package",
      migrationsDir = file("src") / "main" / "resources" / "db" / "migration",
    )
  )  

def skunkCodeGenTask(
  pkgName: String,
  migrationsDir: File,
  excludeTables: List[String] = Nil,
) = Def.task {
  import sys.process.*
  import scala.jdk.CollectionConverters.*
  import java.nio.file.Files

  val logger    = streams.value.log
  val outDir    = (Compile / sourceManaged).value
  val outPkgDir = outDir / pkgName.split('.').mkString(java.io.File.separator)

  val cmd = List(
    "./path/to/codegen_x86_64",
    s"-output-dir=${outDir.getPath()}",
    s"-pkg-name=$pkgName",
    s"-source-dir=${migrationsDir.getPath()}",
    """-use-docker-image="postgres:17-alpine"""",
    s"-exclude-tables=${excludeTables.mkString(",")}",
    "-force=false",
  ).mkString(" ")

  logger.debug(cmd)

  val errs = scala.collection.mutable.ListBuffer.empty[String]
  cmd ! ProcessLogger(i => logger.info(s"[Skunk codegen] $i"), e => errs += e) match {
    case 0 => ()
    case c => throw new InterruptedException(s"Failure on code generation:\n${errs.mkString("\n")}")
  }

  Files
    .walk(outPkgDir.toPath)
    .iterator()
    .asScala
    .collect {
      case p if !Files.isDirectory(p) => p.toFile
    }
    .toList
}
```

**Command line arguments:**
- `-output-dir` (required): Output directory for generated Scala files
- `-pkg-name` (required): Scala package name for generated code
- `-source-dir` (required): Directory containing migration SQL files
- `-use-docker-image`: Docker image for Postgres (default: postgres:17-alpine)
- `-use-connection`: Use a custom Postgres connection URI (overrides Docker)
- `-exclude-tables`: Comma-separated list of tables to exclude
- `-scala-version`: Scala version (default: 3.7.1)
- `-debug`: Enable debug output (true/1 to enable)
- `-force`: Force code generation, ignoring cache (true/1 to enable)

## Output

- Scala files for each table and enum in the specified package directory.
- Type-safe codecs and helper methods for querying and updating tables.
- Support for array types, nullable columns, and enum mappings.

---

For more details, see the code in [`PgCodeGen.scala`](PgCodeGen.scala).