# Sbt plugin for generating source code from Postgres database schema

![Maven Central Version](https://img.shields.io/maven-central/v/com.anymindgroup/skunk-codegen_2.12)

## Usage

Add plugin to `project/plugins.sbt`
```scala
addSbtPlugin("com.anymindgroup" % "sbt-skunk-codegen" % "x.y.z")
```

Enable plugin for the project and configure:
```scala
lazy val myProject = (project in file("."))
  .enablePlugins(PgCodeGenPlugin)
  .settings(
    name := "test",
    Compile / scalacOptions ++= Seq("-Xsource:3", "-release:17"),
    // Generator settings
    pgCodeGenOutputPackage  := "com.example", // output package
    
    // postgres connection settings
    pgCodeGenHost           := "localhost", // default: "localhost"
    pgCodeGenPort           := 5432,
    pgCodeGenUser           := "postgres", // default: "postgres"
    pgCodeGenDb             := "postgres", // default: "postgres"
    pgCodeGenPassword       := Some("postgres"), // default: None
    // pgCodeGenOperateDB value will create new database with specified
    // name if not exist for pgCodeGen migration process. Recommend to be configure differently
    // with multiple module in the same project
    pgCodeGenOperateDB      := Some("postgres_b") // default: None

    // whether to start a postgres docker container and what image to use on running the task (default: Some("postgres:16-alpine"))
    pgCodeGenUseDockerImage      := Some("postgres:16-alpine"),

    // path to directory with sql migration script
    pgCodeGenSqlSourceDir   := file("src") / "main" / "resources" / "db" / "migration",
    
    // list of tables to exclude from generator
    pgCodeGenExcludedTables := List("to_exclude_table_name")
  )
```
See all available settings under [PgCodeGenPlugin.scala](modules/sbt/src/main/scala/com/anymindgroup/sbt/PgCodeGenPlugin.scala).  
See example setup under [sbt test](modules/sbt/src/sbt-test/test/basic).  

Generator will run on changes to sql migration scripts.  
Watch and re-compile on changes by e.g.:
```shell
sbt ~compile
```

To force code re-generation, execute the task
```shell
sbt pgCodeGen
```
