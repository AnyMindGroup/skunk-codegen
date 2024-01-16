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

    lazy val pgCodeGenDb: SettingKey[Option[String]] =
      settingKey[Option[String]]("Postgres database name leaving as None will create and connect to random db")

    lazy val pgCodeGenUseDockerImage: SettingKey[Option[String]] =
      settingKey[Option[String]]("Whether to use docker and what image")

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
      pgCodeGenDb             := None,
      pgCodeGenPassword       := None,
      pgCodeGenSqlSourceDir   := file("src") / "main" / "resources" / "db" / "migration",
      pgCodeGenOutputPackage  := "anychat.chat.db",
      pgCodeGenOutputDir      := (Compile / sourceManaged).value,
      pgCodeGenExcludedTables := Nil,
      pgCodeGenUseDockerImage := Some("postgres:14-alpine"),
      pgCodeGen := {
        new PgCodeGen(
          host = pgCodeGenHost.value,
          port = pgCodeGenPort.value,
          user = pgCodeGenUser.value,
          password = pgCodeGenPassword.value,
          inputDB = pgCodeGenDb.value,
          outputDir = pgCodeGenOutputDir.value,
          pkgName = pgCodeGenOutputPackage.value,
          sourceDir = pgCodeGenSqlSourceDir.value,
          useDockerImage = pgCodeGenUseDockerImage.value,
          excludeTables = pgCodeGenExcludedTables.value,
          scalaVersion = scalaVersion.value,
        ).unsafeRunSync(true)
      },
      Compile / sourceGenerators += Def.task {
        new PgCodeGen(
          host = pgCodeGenHost.value,
          port = pgCodeGenPort.value,
          user = pgCodeGenUser.value,
          password = pgCodeGenPassword.value,
          inputDB = pgCodeGenDb.value,
          outputDir = pgCodeGenOutputDir.value,
          pkgName = pgCodeGenOutputPackage.value,
          sourceDir = pgCodeGenSqlSourceDir.value,
          useDockerImage = pgCodeGenUseDockerImage.value,
          excludeTables = pgCodeGenExcludedTables.value,
          scalaVersion = scalaVersion.value,
        ).unsafeRunSync()
      }.taskValue,
    )
}
