//> using scala 3.7.1
//> using dep com.indoorvivants.roach::core::0.1.0
//> using platform native
//> using nativeVersion 0.5.8
//> using file ../../main/scala/PgCodeGen.scala

package com.anymindgroup

import java.nio.file.Files
import java.nio.file.CopyOption
import java.nio.file.StandardCopyOption
import java.nio.file.Paths
import java.nio.file.Path
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Failure
import scala.util.Success

@main def run(args: String*) =
  val baseSrcDir   = Paths.get("src", "test")
  val scalaVersion = "3.7.1"

  println(s"Running test for scala version $scalaVersion")

  val scalaOutDir = scalaVersion.split('.') match {
    case Array("3", _*)       => "scala"
    case Array("2", "12", _*) => "scala-2.12"
    case Array("2", "13", _*) => "scala-2.13"
    case _                    => "scala"
  }
  val scalaOutPkgDir     = baseSrcDir / scalaOutDir
  val scalaTestOutPkgDir = scalaOutPkgDir / "generated"

  Files.deleteIfExists(scalaTestOutPkgDir)

  Await
    .ready(
      PgCodeGen(
        host = "localhost",
        user = "postgres",
        database = "postgres",
        operateDatabase = Some("new_db"),
        port = sys.env.get("CI").fold(5434)(_ => 5432),
        password = Some("postgres"),
        useDockerImage = sys.env.get("CI").fold(Option("postgres:17-alpine"))(_ => None),
        outputDir = (baseSrcDir / scalaOutDir).toFile(),
        pkgName = "com.anymindgroup.generated",
        sourceDir = (baseSrcDir / "resources" / "db" / "migration").toFile(),
        excludeTables = List("unsupported_yet"),
        scalaVersion = scalaVersion,
      ).run(forceRegeneration = true),
      30.seconds,
    )
    .onComplete {
      case Failure(err) =>
        Console.err.println(s"Failure ${err.getMessage()}")
        sys.exit(1)
      case Success(files) =>
        println(s"Generated ${files.length} files")

        val testRunFile     = baseSrcDir / "scala" / "GeneratedCodeTest._scala"
        val testRunDistFile = scalaOutPkgDir / "GeneratedCodeTest.scala"

        Files.copy(
          testRunFile,
          testRunDistFile,
          StandardCopyOption.REPLACE_EXISTING,
        )
    }
