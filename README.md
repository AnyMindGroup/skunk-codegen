# Sbt plugin for generating source code from Postgres database schema

[![CircleCI](https://dl.circleci.com/status-badge/img/gh/AnyMindGroup/sbt-skunk-codegen/tree/master.svg?style=svg&circle-token=af4c1892eed3931096ecec734e8e318c8cc192a4)](https://dl.circleci.com/status-badge/redirect/gh/AnyMindGroup/sbt-skunk-codegen/tree/master)

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

    // whether to start a postgres docker container on running the task (default: true)
    pgCodeGenUseDocker      := true,

    // path to directory with sql migration script
    pgCodeGenSqlSourceDir   := file("src") / "main" / "resources" / "db" / "migration",
    
    // list of tables to exclude from generator
    pgCodeGenExcludedTables := List("to_exclude_table_name"),
    // ...
  )
```
See all available settings under [PgCodeGenPlugin.scala](modules/sbt/src/main/scala/com/anymindgroup/sbt/PgCodeGenPlugin.scala).  
See example setup under [sbt test](modules/sbt/src/sbt-test/test/basic).  

Generator will run on changes to sql migration scripts.  
Watch and re-compile on changes by e.g.:
```
sbt ~compile
```

To force code re-generation, execute the task
```
sbt pgCodeGen
```
