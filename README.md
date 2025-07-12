# skunk-codegen

`skunk-codegen` is a Scala 3 code generator for PostgreSQL database schemas. It introspects your database and generates type-safe Scala code for use with the [skunk](https://tpolecat.github.io/skunk/) functional Postgres library.

The code generator is based on [roach](https://github.com/indoorvivants/roach), an experimental Scala Native library for Postgres access using libpq and is shipped with a command line as native binary (~6MB).

## Features

- **Schema Introspection:** Reads tables, columns, constraints (primary, unique, foreign keys), indexes, and enums from a PostgreSQL database.
- **Code Generation:** Produces Scala case classes, codecs, and table definitions for each table and enum in your schema.
- **Migration Support:** Runs Flyway compatible database migrations.
- **Docker Integration:** Can spin up a PostgreSQL Docker container for isolated code generation.
- **Customizable:** Supports excluding tables, specifying output/source directories, and customizing package names.

## Usage

Run the generator via command line:  
(_Ensure `libpq` and `docker` are installed on your system_)

```shell
# download executable (for Linux / x86_64)
curl https://github.com/AnyMindGroup/skunk-codegen/releases/download/latest/skunk-codegen-x86_64-linux > skunk_codegen && chmod +x skunk_codegen

# run code generator
./skunk_codegen \
  -use-docker-image="postgres:17-alpine" \
  -output-dir=my/out/dir \
  -pkg-name=my.package \
  -exclude-tables=table_name_a,table_name_b \
  -source-dir=path/to/db/migrations
```

**Command line arguments:**
- `-output-dir` (required): Output directory for generated Scala files
- `-pkg-name` (required): Scala package name for generated code
- `-source-dir` (required): Directory containing migration SQL files
- `-use-docker-image`: Docker image for Postgres (default: postgres:17-alpine)
- `-use-connection`: Use a custom Postgres connection URI (will not boot up a new Postgres Docker container if set)
- `-exclude-tables`: Comma-separated list of tables to exclude
- `-scala-version`: Scala version (default: 3.7.1)
- `-debug`: Enable debug output (`true`/`1` to enable)
- `-force`: Force code generation, ignoring cache (`true`/`1` to enable)

#### Example usage of command line as source generator in [sbt](https://www.scala-sbt.org):

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
    "./path/to/skunk_codegen",
    s"-output-dir=${outDir.getPath()}",
    s"-pkg-name=$pkgName",
    s"-source-dir=${migrationsDir.getPath()}",
    s"-exclude-tables=${excludeTables.mkString(",")}",
    "-force=false",
  ).mkString(" ")

  logger.debug(s"Running skunk code generator with: $cmd")

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


## Output

- Scala files for each table and enum in the specified package directory.
- Type-safe codecs and helper methods for querying and updating tables.
- Support for array types, nullable columns, and enum mappings.

---

For more details, see the code in [`PgCodeGen.scala`](PgCodeGen.scala).