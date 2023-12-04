lazy val scala212 = "2.12.18"
lazy val scala213 = "2.13.12"
lazy val scala3   = "3.3.1"
lazy val allScala = Seq(scala212, scala213, scala3)

ThisBuild / organization := "com.anymindgroup"
ThisBuild / licenses     := Seq(License.Apache2)
ThisBuild / developers := List(
  Developer("rolang", "Roman Langolf", "@rolang", url("https://github.com/rolang")),
  Developer("dutch3883", "Panuwach Boonyasup", "@dutch3883", url("https://github.com/dutch3883")),
  Developer("qhquanghuy", "Huy Nguyen", "@qhquanghuy", url("https://github.com/qhquanghuy")),
)

lazy val betterFilesVersion = "3.9.2"
lazy val commonSettings = List(
  libraryDependencies ++= {
    if (scalaVersion.value == scala3)
      Seq()
    else
      Seq(compilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"))
  },
  credentials += {
    for {
      username <- sys.env.get("ARTIFACT_REGISTRY_USERNAME")
      apiKey   <- sys.env.get("ARTIFACT_REGISTRY_PASSWORD")
    } yield Credentials("https://asia-maven.pkg.dev", "asia-maven.pkg.dev", username, apiKey)
  }.getOrElse(Credentials(Path.userHome / ".ivy2" / ".credentials")),
  resolvers ++= Seq(
    "AnyChat Registry Release" at "https://asia-maven.pkg.dev/anychat-staging/maven-release"
  ),
  version ~= { v => if (v.contains('+')) s"${v.replace('+', '-')}-SNAPSHOT" else v },
)

lazy val root = (project in file("."))
  .dependsOn(core, sbtPlugin)
  .aggregate(core, sbtPlugin)
  .settings(noPublishSettings)

val noPublishSettings = List(
  publish         := {},
  publishLocal    := {},
  publishArtifact := false,
  publish / skip  := true,
)

val releaseSettings = List(
  publishTo := {
    val pkgDev = "https://asia-maven.pkg.dev/anychat-staging"
    if (isSnapshot.value)
      Some("https://asia-maven.pkg.dev" at pkgDev + "/maven-snapshot")
    else
      Some("https://asia-maven.pkg.dev" at pkgDev + "/maven-release")
  }
)

lazy val core = (project in file("modules/core"))
  .settings(
    name               := "skunk-codegen",
    scalaVersion       := scala213,
    crossScalaVersions := allScala,
    javacOptions ++= Seq("-source", "17", "-target", "17"),
    Compile / scalacOptions ++= {
      Seq("-release:17") ++ {
        if (scalaVersion.value == scala3)
          Seq("-source:future")
        else if (scalaVersion.value == scala213)
          Seq("-Ymacro-annotations", "-Xsource:3", "-Wconf:cat=scala3-migration:s") // https://github.com/scala/scala/pull/10439
        else
          Seq("-Xsource:3")
      }
    },
    libraryDependencies ++= Seq(
      "dev.rolang"           %% "dumbo"        % "0.0.5",
      "com.github.pathikrit" %% "better-files" % betterFilesVersion,
    ),
  )
  .settings(commonSettings)
  .settings(releaseSettings)

lazy val sbtPlugin = (project in file("modules/sbt"))
  .enablePlugins(SbtPlugin)
  .dependsOn(core)
  .aggregate(core)
  .settings(commonSettings)
  .settings(releaseSettings)
  .settings(
    name         := "sbt-skunk-codegen",
    scalaVersion := "2.12.18",
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog := false,
  )
