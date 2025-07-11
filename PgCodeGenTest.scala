//> using scala 3.7.1
//> using dep dev.rolang::dumbo:0.5.5
//> using platform jvm
//> using jvm system
//> using file test-generated/generated

import scala.annotation.tailrec
import scala.concurrent.duration.*
import scala.util.Random

import java.net.ServerSocket
import java.time.{OffsetDateTime, ZoneOffset}
import java.time.temporal.ChronoUnit

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.Console
import cats.implicits.*
import dumbo.ConnectionConfig
import fs2.io.file.Path
import generated.*
import org.typelevel.otel4s.trace.Tracer.Implicits.noop
import skunk.*
import skunk.codec.all.*
import skunk.implicits.*
import skunk.util.{Origin, Typer}
import sys.process.*

object GeneratedCodeTest extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    (for
      testDbPort <- IO(findFreePort())
      _ <- IO(
        List(
          "docker run",
          s"-p $testDbPort:5432",
          "-h localhost",
          "-e POSTGRES_USER=postgres",
          "-e POSTGRES_PASSWORD=postgres",
          "--name codegen-test",
          "-d",
          "postgres:17-alpine"
        ).mkString(" ").!!
      )
      _ <- awaitReadiness(testDbPort)
      _ <- migrate(testDbPort)
      _ <- Session
        .single[IO](
          host = "localhost",
          port = testDbPort,
          user = "postgres",
          database = "postgres",
          password = Some("postgres"),
          strategy = Typer.Strategy.SearchPath // to include custom types like enums,
        )
        .use { s =>
          val (testRow, testUpdateFr) = TestRow(
            number = Some(1),
            createdAt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS),
            template = Some(TestEnumType.T1One),
            name = Some("name"),
            name2 = "name2",
            `type` = Some("type"),
            tla = "abc",
            tlaVar = "abc",
            numericDefault = BigDecimal(1),
            numeric24p = BigDecimal(2),
            numeric16p2s = BigDecimal(3)
          ).withUpdateAll

          val testBRow = TestBRow(
            keyA = "keyA",
            keyB = "keyB",
            val1 = "val1",
            val2 = "val2",
            val3 = "val3",
            val4 = "val4",
            val5 = "val5",
            val6 = "val6",
            val7 = "val7",
            val8 = "val8",
            val9 = "val9",
            val10 = "val10",
            val11 = "val11",
            val12 = "val12",
            val13 = "val13",
            val14 = "val14",
            val15 = "val15",
            val16 = "val16",
            val17 = "val17",
            val18 = "val18",
            val19 = "val19",
            val20 = "val20",
            val21 = "val21",
            val22 = "val22",
            val23 = "val23",
            val24 = "val24",
            val25 = "val25",
            val26 = List("val26"),
            val27 = Some(List(1, 2)),
            date = None
          )
          for {
            // Test table
            p <- s.prepare(TestTable.upsertQuery(testUpdateFr))
            _ <- s.prepare(TestTable.insertQuery(ignoreConflict = true))
            res <- p.option((testRow, testUpdateFr.argument))
            _ <- IO.raiseWhen(res.isEmpty)(new Throwable("test A did not return generated columns"))
            id = res.get._1
            _ <- IO.raiseWhen(res.get._2 != 2 && res.get._3 != Some(2))(
              new Throwable("unexpected result for generated columns")
            )
            all <- s.execute(TestTable.selectAll())
            allWithGen <- s.execute(TestTable.selectAllWithGenerated())
            _ <- IO.raiseWhen(all != List(testRow))(new Throwable("test A result not equal"))
            _ <- IO.raiseWhen(allWithGen.map(_._4) != List(testRow))(new Throwable("test A result with id not equal"))
            aliasedTestTable = TestTable.withAlias("t")
            idAndName2 = aliasedTestTable.column.id ~ aliasedTestTable.column.name_2
            xs <-
              s.execute(
                sql"""SELECT #${idAndName2.aliasedName},#${aliasedTestTable.column.name.fullName} FROM #${TestTable.tableName} #${aliasedTestTable.tableName}"""
                  .query(idAndName2.codec ~ TestTable.column.name.codec)
              )
            _ <- IO.raiseWhen(xs != List((id, testRow.name2) -> testRow.name))(
              new Throwable("test A select fields not equal")
            )
            all2 <- s.execute(TestTable.select(TestTable.all))
            _ <- IO.raiseWhen(all2 != List(testRow))(new Throwable("test A select all fields not equal"))
            // TestB table
            testBUpdateAllFr = testBRow.withUpdateAll._2

            upsertCmd <- s.prepare(TestBTable.upsertQuery(testBUpdateAllFr))
            _ <- upsertCmd.execute((testBRow, testBUpdateAllFr.argument))
            allLoaded <- s.execute(TestBTable.selectAll())
            _ <- IO.raiseWhen(List(testBRow) != allLoaded)(new Throwable("test B result not equal"))
            loadByIdQ <- s.prepare(TestBTable.selectAll(sql"WHERE key_a = ${varchar} AND key_b = ${varchar}"))
            loadedById <- loadByIdQ.option((testBRow.keyA, testBRow.keyB))
            _ <- IO.raiseWhen(Some(testBRow) != loadedById)(new Throwable("test B result by id not equal"))
            notFoundRes <- s.execute(TestBTable.selectAll(sql"WHERE key_a = 'not_existing'"))
            _ <- IO.raiseWhen(notFoundRes.nonEmpty)(new Throwable("test B query result is empty"))

            testBRowUpdate = testBRow.copy(val1 = "val1_update", val2 = "val2_update")
            testBUpdateFr = testBRowUpdate.withUpdate(_.copy(val2 = None))._2 // exclude update of val2

            _ <- s.execute(TestBTable.upsertQuery(testBUpdateFr))((testBRowUpdate, testBUpdateFr.argument))
            afterUpdate <- s.execute(TestBTable.selectAll())
            _ <- IO.raiseWhen(afterUpdate.length != 1)(new Throwable("test B result unexpected length"))
            _ <-
              IO.raiseWhen(
                afterUpdate.headOption.map(_.val1) != Some("val1_update") ||
                  afterUpdate.headOption.map(_.val2) == Some("val2_update") // should not be updated
              )(
                new Throwable("test B result unexpected update")
              )
            // Check Enum variable format
            _ = Seq(
              TestEnumType.T1One,
              TestEnumType.T2Two,
              TestEnumType.T3Three,
              TestEnumType.T4Four,
              TestEnumType.T5Five,
              TestEnumType.T6six,
              TestEnumType.MultipleWordEnum
            )
            _ <- s.execute(sql"TRUNCATE TABLE #${TestBTable.tableName}".command)

            _ <- s.execute(TestBTable.insert0(TestBTable.all))(testBRow)
            allBTable <- s.execute(TestBTable.select(TestBTable.all))
            _ <- IO.raiseWhen(allBTable != List(testBRow))(new Throwable("test B not equal"))
            loadedById <-
              s.option(
                TestBTable.select(
                  TestBTable.all,
                  sql"WHERE #${TestBTable.column.key_a.name} = ${TestBTable.column.key_a.codec} AND #${TestBTable.column.key_b.name} = ${TestBTable.column.key_b.codec}"
                )
              )((testBRow.keyA, testBRow.keyB))

            _ <- IO.raiseWhen(Some(testBRow) != loadedById)(new Throwable("test B result by id not equal"))

            _ <-
              s.execute(
                TestBTable.select(
                  TestBTable.column.key_a ~ TestBTable.column.key_b ~ TestBTable.column.val_1,
                  sql"WHERE key_a = 'not_existing'"
                )
              ).flatMap(notFoundRes =>
                IO.raiseWhen(notFoundRes.nonEmpty)(new Throwable("test B query result is empty"))
              )

            updatingFields =
              TestBTable.column.val_27(None) ~ TestBTable.column.val_2("updated_val_2") ~ TestBTable.column.val_14(
                "updated_val_14"
              )
            updateQ = sql"""
          ON CONFLICT ON CONSTRAINT #${generated.constraints.testBPkey.name} DO UPDATE SET
          (#${updatingFields.name}) = (${updatingFields.codec})
          """
            _ <- s.execute(TestBTable.insert(TestBTable.all, updateQ))(testBRow *: updatingFields.value *: EmptyTuple)
            loadedById <-
              s.option(
                TestBTable.select(
                  TestBTable.all,
                  sql"WHERE #${TestBTable.column.key_a.name} = ${TestBTable.column.key_a.codec} AND #${TestBTable.column.key_b.name} = ${TestBTable.column.key_b.codec}"
                )
              )((testBRow.keyA, testBRow.keyB))
            _ <- IO.raiseWhen(
              Some(testBRow.copy(val27 = None, val2 = "updated_val_2", val14 = "updated_val_14")) != loadedById
            )(new Throwable("test B result missing update"))
            _ <- s.execute(sql"REFRESH MATERIALIZED VIEW test_materialized_view".command)
            result <- s.execute(TestMaterializedViewTable.selectAll())
            _ <- IO.raiseWhen(result.isEmpty)(new Throwable(s"materialized view doesn't have correct value: ${result}"))
            _ <- IO.println("Test successful!")
          } yield ()
        }
    yield ()).attempt.flatMap:
      case Right(_)  => IO("docker rm -f codegen-test".!!).as(ExitCode.Success)
      case Left(err) => Console[IO].printStackTrace(err) >> IO("docker rm -f codegen-test".!!).as(ExitCode.Error)

  private def migrate(port: Int) = dumbo.Dumbo
    .withFilesIn[IO](Path("test/migrations"))
    .apply(
      connection = ConnectionConfig(
        host = "localhost",
        port = port,
        user = "postgres",
        database = "postgres",
        password = Some("postgres")
      )
    )
    .runMigration

  @tailrec
  private def findFreePort(): Int =
    try
      val portCandidate = 1024 + Random.nextInt(65535 - 1024)
      val socket = new ServerSocket(portCandidate)
      val port = socket.getLocalPort
      socket.close()
      port
    catch case e: Throwable => findFreePort()

  private def awaitReadiness(port: Int) =
    fs2.Stream
      .repeatEval(
        Session
          .single[IO](
            host = "localhost",
            port = port,
            user = "postgres",
            database = "postgres",
            password = Some("postgres")
          )
          .use(_.unique(sql"SELECT 1".query(int4)).void)
          .attempt
          .map(_.swap.toOption)
      )
      .metered(500.millis)
      .timeout(10.seconds)
      .unNoneTerminate
      .compile
      .drain
      .onError(e => IO.println(s"Could not connect to docker on localhost:$port ${e.getMessage()}"))
}
