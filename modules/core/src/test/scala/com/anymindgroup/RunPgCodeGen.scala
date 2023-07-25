package com.anymindgroup

import cats.effect.IOApp
import cats.effect.{ExitCode, IO}
import better.files.*
import com.anymindgroup.testsupport.scalaVersion

object RunPgCodeGen extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = for {
    baseSrcDir <- IO(File("modules") / "core" / "src" / "test")
    _          <- IO.println(s"Running test for scala version $scalaVersion")
    scalaOutDir = scalaVersion.split('.') match {
                    case Array("3", _*)       => "scala-3"
                    case Array("2", "12", _*) => "scala-2.12"
                    case Array("2", "13", _*) => "scala-2.13"
                    case _                    => "scala"
                  }
    scalaOutPkgDir     = baseSrcDir / scalaOutDir / "com" / "anymindgroup"
    scalaTestOutPkgDir = scalaOutPkgDir / "generated"
    _                 <- IO(scalaTestOutPkgDir.delete(true))
    _ <- new PgCodeGen(
           host = "localhost",
           user = "postgres",
           database = "postgres",
           port = sys.env.get("CI").fold(5434)(_ => 5432),
           password = Some("postgres"),
           useDocker = sys.env.get("CI").fold(true)(_ => false),
           outputDir = (baseSrcDir / scalaOutDir).toJava,
           pkgName = "com.anymindgroup.generated",
           sourceDir = (baseSrcDir / "resources" / "db" / "migration").toJava,
           excludeTables = List("unsupported_yet"),
           scalaVersion = scalaVersion,
         ).run()
    testRunFile = baseSrcDir / "scala" / "com" / "anymindgroup" / "GeneratedCodeTest._scala"
    _          <- IO.whenA(testRunFile.exists)(IO(testRunFile.copyTo(scalaOutPkgDir / "GeneratedCodeTest.scala", true)).void)
  } yield ExitCode.Success

}
