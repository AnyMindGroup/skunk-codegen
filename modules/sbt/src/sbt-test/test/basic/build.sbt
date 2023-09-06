crossScalaVersions := Seq("3.3.0", "2.13.11")

val skunkVersion = "0.6.0"

lazy val testRoot = (project in file("."))
  .enablePlugins(PgCodeGenPlugin)
  .settings(
    name := "test",
    Compile / scalacOptions ++= {
      if (scalaVersion.value.startsWith("3"))
        Seq("-source:future")
      else
        Seq("-Xsource:3")
    },
    pgCodeGenOutputPackage  := "com.example",
    pgCodeGenPassword       := Some("postgres"),
    pgCodeGenPort           := sys.env.get("CI").fold(5434)(_ => 5432),
    pgCodeGenUseDocker      := !sys.env.contains("CI"),
    pgCodeGenSqlSourceDir   := file("resources") / "db" / "migration",
    pgCodeGenExcludedTables := List("unsupported_yet"),
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "skunk-core" % skunkVersion
    ),
  )
