crossScalaVersions := Seq( /*"3.2.2",*/ "2.13.11")

val skunkVersion = "0.6.0"

lazy val testRoot = (project in file("."))
  .enablePlugins(PgCodeGenPlugin)
  .settings(
    name := "test",
    Compile / scalacOptions ++= Seq("-Xsource:3", "-release:17"),
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
