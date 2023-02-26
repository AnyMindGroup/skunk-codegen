package com.anymindgroup

import cats.effect.IOApp
import cats.effect.{ExitCode, IO}
import better.files._

object PgCodeGenManuaTest extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = for {
    baseSrcDir <- IO(File("modules") / "core" / "src" / "test")
    _ <- new PgCodeGen(
           host = "localhost",
           user = "postgres",
           database = "postgres",
           port = 5434,
           password = Some("postgres"),
           useDocker = true,
           outputDir = (baseSrcDir / "scala").toJava,
           pkgName = "com.anymindgroup.manual_test",
           sourceDir = (baseSrcDir / "resources" / "db" / "migration").toJava,
         ).run()
  } yield ExitCode.Success

}
