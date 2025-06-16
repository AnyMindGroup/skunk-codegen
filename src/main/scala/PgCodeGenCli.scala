//> using scala 3.7.1
//> using platform native
//> using nativeVersion 0.5.8
//> using file PgCodeGen.scala

package com.anymindgroup
import java.io.File
import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext.Implicits.global

@main
def run(args: String*) =
  val argsMap = args.toList
    .flatMap(_.split('=').map(_.trim().toLowerCase()))
    .sliding(2, 2)
    .collect { case a :: b :: _ => a -> b }
    .toMap

  (for
    host           <- argsMap.get("-host").toRight("host not set")
    user           <- argsMap.get("-user").toRight("user not set")
    database       <- argsMap.get("-database").toRight("database not set")
    operateDatabase = argsMap.get("-operateDatabase")
    port           <- argsMap.get("-port").flatMap(_.toIntOption).toRight("missing or invalid port")
    password        = argsMap.get("-password")
    useDockerImage  = argsMap.get("-useDockerImage")
    outputDir      <- argsMap.get("-outputDir").map(File(_)).toRight("outputDir not set")
    pkgName        <- argsMap.get("-pkgName").toRight("pkgName not set")
    sourceDir      <- argsMap.get("-sourceDir").map(File(_)).toRight("sourceDir not set")
    excludeTables   = argsMap.get("-excludeTables").toList.flatMap(_.split(","))
    scalaVersion   <- argsMap.get("-scalaVersion").toRight("scalaVersion not set")
  yield PgCodeGen(
    host = host,
    user = user,
    database = database,
    operateDatabase = operateDatabase,
    port = port,
    password = password,
    useDockerImage = useDockerImage,
    outputDir = outputDir,
    pkgName = pkgName,
    sourceDir = sourceDir,
    excludeTables = excludeTables,
    scalaVersion = scalaVersion,
  )) match
    case Right(codegen) =>
      Await
        .ready(codegen.run(true), 30.seconds)
        .onComplete:
          case Failure(err) =>
            Console.err.println(s"Failure: ${err.printStackTrace()}")
            sys.exit(1)
          case Success(files) =>
            println(s"Generated ${files.length} files")
            sys.exit(0)

    case Left(err) =>
      Console.err.println(s"Failure: $err")
      sys.exit(1)
