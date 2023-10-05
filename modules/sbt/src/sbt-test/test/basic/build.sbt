crossScalaVersions := Seq("3.3.1", "2.13.12")

val skunkVersion = "0.6.0"

lazy val testRoot = (project in file("."))
  .enablePlugins(PgCodeGenPlugin)
  .settings(
    name := "test",
    Compile / scalacOptions ++= {
      if (scalaVersion.value.startsWith("3"))
        Seq("-source:future")
      else
        Seq("-Xsource:3", "-Wconf:cat=scala3-migration:s")
    },
    pgCodeGenOutputPackage  := "com.example",
    pgCodeGenPassword       := Some("postgres"),
    pgCodeGenPort           := sys.env.get("CI").fold(5434)(_ => 5432),
    pgCodeGenUseDockerImage := sys.env.get("CI").fold(Option("postgres:14-alpine"))(_ => None),
    pgCodeGenSqlSourceDir   := file("resources") / "db" / "migration",
    pgCodeGenExcludedTables := List("unsupported_yet"),
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "skunk-core" % skunkVersion
    ),
  )
