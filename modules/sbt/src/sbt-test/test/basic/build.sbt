crossScalaVersions := Seq( /*"3.2.2",*/ "2.13.10")

val skunkVersion       = "0.5.1"
val betterFilesVersion = "3.9.2"

lazy val testRoot = (project in file("."))
  .enablePlugins(PgCodeGenPlugin)
  .settings(
    name         := "test",
    version      := "0.1",
    organization := "test",
    javacOptions ++= Seq("-source", "17", "-target", "17"),
    Compile / scalacOptions ++= Seq("-Xsource:3", "-release:17"),
    pgCodeGenOutputPackage := "com.example",
    pgCodeGenPassword      := Some("postgres"),
    pgCodeGenPort          := sys.env.get("CI").fold(5434)(_ => 5432),
    pgCodeGenUseDocker     := !sys.env.contains("CI"),
    pgCodeGenSqlSourceDir  := file("resources") / "db" / "migration",
    libraryDependencies ++= Seq(
      "org.tpolecat"         %% "skunk-core"   % skunkVersion,
      "com.github.pathikrit" %% "better-files" % betterFilesVersion,
    ),
  )
