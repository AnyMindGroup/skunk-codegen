package com.anymindgroup

import cats.effect.IOApp
import cats.effect.{ExitCode, IO}
import better.files._

object RunPgCodeGen extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = for {
    baseSrcDir     <- IO(File("modules") / "core" / "src" / "test")
    scalaBasePkgDir = baseSrcDir / "scala" / "com" / "anymindgroup"
    scalaTestPkgDir = scalaBasePkgDir / "generated"
    _              <- IO(scalaTestPkgDir.delete(true))
    _ <- new PgCodeGen(
           host = "localhost",
           user = "postgres",
           database = "postgres",
           port = sys.env.get("CI").fold(5434)(_ => 5432),
           password = Some("postgres"),
           useDocker = sys.env.get("CI").fold(true)(_ => false),
           outputDir = (baseSrcDir / "scala").toJava,
           pkgName = "com.anymindgroup.generated",
           sourceDir = (baseSrcDir / "resources" / "db" / "migration").toJava,
           excludeTables = List("unsupported_yet"),
         ).run()
    testRunFile = scalaBasePkgDir / "GeneratedCodeTest._scala"
    _          <- IO.whenA(testRunFile.exists)(IO(testRunFile.copyTo(scalaBasePkgDir / "GeneratedCodeTest.scala", true)).void)
  } yield ExitCode.Success

}
