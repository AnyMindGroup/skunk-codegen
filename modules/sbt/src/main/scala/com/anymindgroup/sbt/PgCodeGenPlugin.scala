package com.anymindgroup.sbt

import sbt._
import sbt.Keys._
import java.io.File
import com.anymindgroup.PgCodeGen

object PgCodeGenPlugin extends AutoPlugin {

  object autoImport {
    lazy val pgCodeGen = taskKey[Seq[File]]("Generate models")

    lazy val pgCodeGenHost: SettingKey[String] =
      settingKey[String]("Postgres host")

    lazy val pgCodeGenPort: SettingKey[Int] =
      settingKey[Int]("Postgres port")

    lazy val pgCodeGenUser: SettingKey[String] =
      settingKey[String]("Postgres user")

    lazy val pgCodeGenPassword: SettingKey[Option[String]] =
      settingKey[Option[String]]("Postgres user password")

    lazy val pgCodeGenDb: SettingKey[String] =
      settingKey[String]("Postgres database name")

    lazy val pgCodeGenUseDocker: SettingKey[Boolean] =
      settingKey[Boolean]("Whether to use docker to start/stop the service")

    lazy val pgCodeGenSqlSourceDir: SettingKey[File] =
      settingKey[File]("Directory of sql scripts")

    lazy val pgCodeGenOutputPackage: SettingKey[String] =
      settingKey[String]("Package of generated code")

    lazy val pgCodeGenOutputDir: SettingKey[File] =
      settingKey[File]("Output directory of generated code")

    lazy val pgCodeGenExcludedTables: SettingKey[List[String]] =
      settingKey[List[String]]("Tables that should be excluded")
  }

  import autoImport._

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      pgCodeGenHost           := "localhost",
      pgCodeGenUser           := "postgres",
      pgCodeGenDb             := "postgres",
      pgCodeGenPassword       := None,
      pgCodeGenSqlSourceDir   := file("src") / "main" / "resources" / "db" / "migration",
      pgCodeGenOutputPackage  := "anychat.chat.db",
      pgCodeGenOutputDir      := (Compile / sourceManaged).value,
      pgCodeGenExcludedTables := Nil,
      pgCodeGenUseDocker      := true,
      pgCodeGen := {
        new PgCodeGen(
          host = pgCodeGenHost.value,
          port = pgCodeGenPort.value,
          user = pgCodeGenUser.value,
          password = pgCodeGenPassword.value,
          database = pgCodeGenDb.value,
          outputDir = pgCodeGenOutputDir.value,
          pkgName = pgCodeGenOutputPackage.value,
          sourceDir = pgCodeGenSqlSourceDir.value,
          useDocker = pgCodeGenUseDocker.value,
          excludeTables = pgCodeGenExcludedTables.value,
        ).unsafeRunSync(true)
      },
      Compile / sourceGenerators += Def.task {
        new PgCodeGen(
          host = pgCodeGenHost.value,
          port = pgCodeGenPort.value,
          user = pgCodeGenUser.value,
          password = pgCodeGenPassword.value,
          database = pgCodeGenDb.value,
          outputDir = pgCodeGenOutputDir.value,
          pkgName = pgCodeGenOutputPackage.value,
          sourceDir = pgCodeGenSqlSourceDir.value,
          useDocker = pgCodeGenUseDocker.value,
          excludeTables = pgCodeGenExcludedTables.value,
        ).unsafeRunSync()
      }.taskValue,
    )
}
