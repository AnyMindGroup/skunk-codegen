package com.anymindgroup

import java.io.{File => JFile}

import cats.effect._
import skunk.{Command => SqlCommand}
import skunk._
import skunk.codec.all._
import skunk.data.Type
import skunk.implicits._
import cats.implicits._
import natchez.Trace.Implicits.noop

import skunk.util.Origin

import scala.concurrent.duration._
import sys.process._
import better.files._
import java.time.Instant
import com.anymindgroup.PgCodeGen.Constraint.PrimaryKey

class PgCodeGen(
  host: String,
  user: String,
  database: String,
  port: Int,
  password: Option[String],
  useDocker: Boolean,
  outputDir: JFile,
  pkgName: String,
  sourceDir: JFile,
) {
  import PgCodeGen._

  private val pkgDir = File(outputDir.toPath(), pkgName.replace('.', JFile.separatorChar))

  private def sourceFiles: List[File] = {
    val srcDir = File(sourceDir.toPath())
    if (srcDir.exists) srcDir.list.filter(_.extension.contains(".sql")).toList else Nil
  }

  private def getConstraints(s: Session[IO]): IO[TableMap[Constraint]] = {
    val q: Query[Void, String ~ String ~ String ~ String ~ String ~ String] =
      sql"""
       SELECT c.table_name, c.constraint_name, c.constraint_type, cu.column_name, cu.table_name, kcu.column_name
       FROM information_schema.table_constraints AS c
       JOIN information_schema.key_column_usage as kcu ON kcu.constraint_name = c.constraint_name
       JOIN information_schema.constraint_column_usage AS cu ON cu.constraint_name = c.constraint_name
       WHERE c.table_schema='public'
      """.query(name ~ name ~ varchar ~ name ~ name ~ name)

    s.execute(q.map { case a ~ b ~ c ~ d ~ e ~ f =>
      ConstraintRow(tableName = a, name = b, typ = c, refCol = d, refTable = e, fromCol = f)
    }).map {
      _.groupBy(_.tableName).map { case (tName, constraints) =>
        (
          tName,
          constraints.groupBy(c => (c.name, c.typ)).toList.map {
            case ((cName, "PRIMARY KEY"), cItems) =>
              Constraint.PrimaryKey(name = cName, columnNames = cItems.map(_.fromCol))
            case ((cName, "UNIQUE"), cItems) => Constraint.Unique(name = cName, columnNames = cItems.map(_.fromCol))
            case ((cName, "FOREIGN KEY"), cItems) =>
              Constraint.ForeignKey(
                name = cName,
                refs = cItems.map { cr =>
                  ColumnRef(fromColName = cr.fromCol, toColName = cr.refCol, toTableName = cr.refTable)
                },
              )
            case ((cName, _), _) => Constraint.Unknown(cName)
          },
        )
      }
    }
  }

  private def getColumns(s: Session[IO], enums: Enums): IO[TableMap[Column]] = {
    val q: Query[Void, String ~ String ~ String ~ String ~ Option[String]] =
      sql"""SELECT table_name,column_name,udt_name,is_nullable,column_default
            FROM information_schema.COLUMNS WHERE table_schema = 'public' AND table_name NOT SIMILAR TO '%\_p\d{1}'
            """.query(name ~ name ~ name ~ varchar(3) ~ varchar.opt)

    s.execute(q.map { case tName ~ colName ~ udt ~ nullable ~ default =>
      val componentTypes = if (udt.startsWith("_")) List(Type(udt.stripPrefix("_"))) else Nil
      (tName, colName, Type(udt, componentTypes), nullable == "YES", default.flatMap(ColumnDefault.fromString))
    }).map(_.map { case (tName, colName, udt, isNullable, default) =>
      toScalaType(udt, isNullable, enums).map { st =>
        (
          tName,
          Column(
            columnName = colName,
            pgType = udt,
            isEnum = enums.exists(_.name == udt.name),
            scalaType = st,
            isNullable = isNullable,
            default = default,
          ),
        )
      }.leftMap(new Exception(_))
    }).flatMap {
      _.traverse(IO.fromEither(_)).map {
        _.groupBy(_._1).map { case (k, v) => (k, v.map(_._2)) }
      }
    }
  }

  private def getIndexes(s: Session[IO]): IO[TableMap[Index]] = {
    val q: Query[Void, String ~ String ~ String] =
      sql"""SELECT indexname,indexdef,tablename FROM pg_indexes WHERE schemaname='public'""".query(name ~ text ~ name)

    s.execute(q.map { case name ~ indexDef ~ tableName =>
      (tableName, Index(name, indexDef))
    }).map {
      _.groupBy(_._1).map { case (tName, v) => (tName, v.map(_._2)) }
    }
  }

  private def getEnums(s: Session[IO]): IO[Enums] = {
    val q: Query[Void, String ~ String] =
      sql"""SELECT pt.typname,pe.enumlabel FROM pg_enum AS pe JOIN pg_type AS pt ON pt.oid = pe.enumtypid""".query(
        name ~ name
      )

    s.execute(q.map { case name ~ value =>
      (name, value)
    }).map {
      _.groupBy(_._1).mapValues(_.map(_._2)).toList.map { case (name, values) =>
        Enum(name, values.map(EnumValue))
      }
    }
  }

  private val generatorTask: IO[List[File]] = Session
    .single[IO](
      host = host,
      port = port,
      user = user,
      database = database,
      password = password,
    )
    .use { s =>
      for {
        _     <- s.execute(sql"DROP SCHEMA public CASCADE;".command)
        _     <- s.execute(sql"CREATE SCHEMA public;".command)
        _     <- runSqlSource(s)
        enums <- getEnums(s)
        ((columns, indexes), constraints) <-
          getColumns(s, enums).parProduct(getIndexes(s)).parProduct(getConstraints(s))
        tables = toTables(columns, indexes, constraints)
        files = pkgFiles(tables, enums) ::: tables.flatMap { table =>
                  List(
                    pkgDir / s"${table.tableClassName}.scala" -> tableFileContent(table),
                    pkgDir / s"${table.rowClassName}.scala"   -> rowFileContent(table),
                  )
                }
        _ <- IO(pkgDir.delete(true).createDirectoryIfNotExists())
        res <- files.parTraverse { case (file, content) =>
                 for {
                   _ <- IO(
                          file
                            .createIfNotExists()
                            .overwrite(content)
                        )
                   _ <- IO.println(s"Created ${file.pathAsString}")
                 } yield file
               }
      } yield res
    }

  private val pgServiceName = pkgName.replace('.', '-')

  private def startDocker: IO[Unit] = if (useDocker) {
    val cmd =
      s"docker run -p $port:5432 -h $host -e POSTGRES_USER=$user ${password.fold("")(p => s"-e POSTGRES_PASSWORD=$p ")}" +
        s"--name $pgServiceName -d postgres:14-alpine"

    IO(cmd.!!) >> IO.sleep(2.seconds)
  } else IO.unit

  private def rmDocker: IO[Unit] = if (useDocker) {
    IO(s"docker rm -f $pgServiceName" ! ProcessLogger(_ => ())).void
  } else IO.unit

  private def toTables(
    columns: TableMap[Column],
    indexes: TableMap[Index],
    constraints: TableMap[Constraint],
  ): List[Table] = {

    def findAutoIncColumns(tableName: TableName): List[Column] =
      columns
        .getOrElse(tableName, Nil)
        .filter(_.default.contains(ColumnDefault.AutoInc))

    def findAutoPk(tableName: TableName): Option[Column] = findAutoIncColumns(tableName)
      .find(col =>
        constraints
          .getOrElse(tableName, Nil)
          .collect { case c: Constraint.PrimaryKey => c }
          .exists(_.columnNames.contains(col.columnName))
      )

    columns.toList.map { case (tname, tableCols) =>
      val tableConstraints = constraints.getOrElse(tname, Nil)
      val autoIncColumns   = findAutoIncColumns(tname)
      val autoIncFk = tableConstraints.collect { case c: Constraint.ForeignKey => c }.flatMap {
        _.refs.flatMap { ref =>
          tableCols.find(c => c.columnName == ref.fromColName).filter { _ =>
            findAutoPk(ref.toTableName).exists(_.columnName == ref.toColName)
          }
        }
      }

      Table(
        name = tname,
        columns = tableCols.filterNot((autoIncColumns ::: autoIncFk).contains),
        autoIncColumns = autoIncColumns,
        constraints = tableConstraints,
        indexes = indexes.getOrElse(tname, Nil),
        autoIncFk = autoIncFk,
      )
    }
  }

  private def scalaEnums(enums: Enums): List[(File, String)] =
    enums.map { e =>
      (
        pkgDir / s"${e.scalaName}.scala",
        s"""|package $pkgName
            |
            |import skunk.Codec
            |import enumeratum.{EnumEntry, Enum}
            |import skunk.data.Type
            |
            |sealed trait ${e.scalaName} extends EnumEntry with EnumEntry.Lowercase
            |object ${e.scalaName} extends Enum[${e.scalaName}] {
            |  ${e.values
             .map(v => s"case object ${v.scalaName} extends ${e.scalaName}")
             .mkString("\n  ")}
            |
            |  val values = findValues
            |
            |  implicit val codec: Codec[${e.scalaName}] = skunk.codec.`enum`.`enum`(${e.scalaName}, Type("${e.name}"))
            |}
            |""".stripMargin,
      )
    }

  private def pkgFiles(tables: List[Table], enums: Enums): List[(File, String)] = {
    val indexes = tables.flatMap { table =>
      table.indexes.map(i =>
        s"""val ${toScalaName(i.name)} = Index(name = "${i.name}", createSql = \"\"\"${i.createSql}\"\"\")"""
      )
    }.mkString("  object indexes {\n    ", "\n    ", "\n  }")

    val constraints = tables.flatMap { table =>
      table.constraints.map(c => s"""val ${toScalaName(c.name)} = Constraint(name = "${c.name}")""")
    }.mkString("  object constraints {\n    ", "\n    ", "\n  }")

    List(
      (
        pkgDir / "package.scala",
        s"""|package ${pkgName.split('.').dropRight(1).mkString(".")}
            |
            |package object ${pkgName.split('.').last} {
            |
            |$indexes
            |
            |$constraints
            |
            |}""".stripMargin,
      ),
      // (
      //   pkgDir / "Column.scala",
      //   s"""|package $pkgName
      //       |
      //       |abstract class Column[T](val name: String) {
      //       |  type Type = T
      //       |  override def toString: String = name
      //       |}
      //       |""".stripMargin,
      // ),
      (
        pkgDir / "Index.scala",
        s"""|package $pkgName
            |
            |final case class Index(name: String, createSql: String)
           """.stripMargin,
      ),
      (
        pkgDir / "Constraint.scala",
        s"""|package $pkgName
            |
            |final case class Constraint(name: String) {
            |  def conflictClause: String = s"ON CONSTRAINT $$name"
            |}
           """.stripMargin,
      ),
    ) ::: scalaEnums(enums)
  }

  private def runSqlSource(session: Session[IO]): IO[Unit] =
    sourceFiles
      .sortBy(f => f.name.drop(1).takeWhile(_ != '_').toInt)
      .flatMap { f =>
        f.lineIterator
          .filterNot(_.trim.startsWith("--"))
          .mkString("\n")
          .split(";")
          .filterNot(l => l.trim.isEmpty || l.contains("ANALYZE VERBOSE"))
          .map { sql =>
            SqlCommand(s"$sql;", Origin(file = f.pathAsString, line = 0), Void.codec)
          }
      }
      .traverse_(session.execute)

  private def toScalaType(t: Type, isNullable: Boolean, enums: Enums): Result[ScalaType] =
    t.componentTypes match {
      case Nil =>
        Map[String, List[Type]](
          "Boolean"                  -> bool.types,
          "String"                   -> (text.types ::: varchar.types ::: bpchar.types ::: name.types),
          "java.util.UUID"           -> uuid.types,
          "Short"                    -> int2.types,
          "Int"                      -> int4.types,
          "Long"                     -> int8.types,
          "BigDecimal"               -> numeric.types,
          "Float"                    -> float4.types,
          "Double"                   -> float8.types,
          "java.time.LocalDate"      -> date.types,
          "java.time.LocalTime"      -> time.types,
          "java.time.OffsetTime"     -> timetz.types,
          "java.time.LocalDateTime"  -> timestamp.types,
          "java.time.OffsetDateTime" -> timestamptz.types,
          "java.time.Duration"       -> List(Type.interval),
        ).collectFirst {
          case (scalaType, pgTypes) if pgTypes.contains(t) => if (isNullable) s"Option[$scalaType]" else scalaType
        }.orElse {
          enums.find(_.name == t.name).map(e => if (isNullable) s"Option[${e.scalaName}]" else e.scalaName)
        }.toRight(s"No scala type found for type ${t.name}")
      case x :: Nil =>
        toScalaType(x, isNullable = false, enums).map(t => if (isNullable) s"Option[List[$t]]" else s"List[$t]")
      case x :: xs =>
        Left(s"Unsupported type of multiple components: ${x :: xs}")
    }

  private def rowFileContent(table: Table): String = {
    import table._

    val colsData = columns
      .map(c => s"    ${c.scalaName}: ${c.scalaType}")
      .mkString("", ",\n", "")

    val codecData = s"${columns.map(_.codecName).mkString(" ~ ")}"

    List(
      s"package $pkgName",
      "",
      "import skunk.*",
      "import skunk.codec.all.*",
      // "import skunk.implicits.*",
      "",
      s"final case class $rowClassName(",
      s"$colsData",
      ")",
      "",
      s"object $rowClassName {",
      s"  implicit val codec: Codec[$rowClassName] = ($codecData).gimap[$rowClassName]",
      "}",
    ).mkString("\n")
  }

  private def tableFileContent(table: Table): String =
    // val colsNames = (table.columns ::: table.autoIncColumns ::: table.autoIncFk)
    //   .map(c =>
    //     s"""        case object ${toScalaName(c.columnName)} extends Column[${c.scalaType}]("${c.columnName}")"""
    //   )
    //   .mkString("\n")

    (
      List(
        s"package $pkgName\n",
        "import skunk.*",
        "import skunk.codec.all.*",
        "import skunk.implicits.*",
      ) :::
        List(
          "",
          s"class ${table.tableClassName}(val tableName: String) {",
          s"  def withPrefix(prefix: String): ${table.tableClassName} = new ${table.tableClassName}(prefix + tableName)",
          "",
          // "  object columns {",
          // s"$colsNames",
          // "  }",
          writeStatements(table),
          selectAllStatement(table),
          "}",
          "",
          s"""object ${table.tableClassName} extends ${table.tableClassName}("${table.name}")""",
        )
    ).mkString("\n")

  private def writeStatements(table: Table): String = {
    import table._

    val uniqueConstr: Option[UniqueConstraint] = constraints.collectFirst { case c: PrimaryKey =>
      c
    }.orElse {
      constraints.collectFirst { case c: Constraint.Unique =>
        c
      }
    }

    val upsertQ = uniqueConstr.map { cstr =>
      val intoCols =
        s"(${(columns ::: autoIncFk).map(_.columnName).filterNot(cstr.columnNames.contains).mkString(",")})"

      val updateCols = (columns ::: autoIncFk).filterNot(c => cstr.columnNames.contains(c.columnName))
      val intoCodecIntr =
        updateCols
          .map(_.codecName)
          .map(t => s"$${$t}")
          .mkString(" ~ ")
      val updateScalaType = updateCols.map(_.scalaType).mkString(" ~ ")

      s"""|  def upsertQuery: Command[$rowClassName ~ $updateScalaType] =
          |    sql"INSERT INTO #$$tableName VALUES $${${rowClassName}.codec} ON CONFLICT ON CONSTRAINT (${cstr.name}) DO UPDATE SET $intoCols=($intoCodecIntr)".command""".stripMargin
    }

    val insertQ =
      s"""|  def insertQuery: Command[$rowClassName] =
          |    sql"INSERT INTO #$$tableName VALUES $${${rowClassName}.codec} ON CONFLICT DO NOTHING".command""".stripMargin

    List(
      upsertQ.getOrElse(""),
      insertQ,
    ).mkString("\n\n")
  }

  private def selectAllStatement(table: Table): String = {
    import table._

    val autoIncStm = if (autoIncColumns.nonEmpty) {

      val types  = autoIncColumns.map(_.codecName).mkString(" ~ ")
      val sTypes = autoIncColumns.map(_.scalaType).mkString(" ~ ")
      val names  = autoIncColumns.map(_.columnName).mkString(", ")

      s"""
         |  def selectAllWithId[A](addClause: Fragment[A] = Fragment.empty): Query[A, $sTypes ~ $rowClassName] =
         |    sql"SELECT $names,${columns
          .map(_.columnName)
          .mkString(",")} FROM #$$tableName $$addClause".query($types ~ ${rowClassName}.codec)
         """.stripMargin
    } else {
      ""
    }

    val defaultStm = s"""
                        |  def selectAll[A](addClause: Fragment[A] = Fragment.empty) =
                        |    sql"SELECT ${columns
                         .map(_.columnName)
                         .mkString(",")} FROM #$$tableName $$addClause".query(${rowClassName}.codec)
    """.stripMargin

    autoIncStm ++ defaultStm
  }

  private def lastModified(files: List[File]): Option[Instant] =
    files match {
      case Nil => none[Instant]
      case fs  => Some(fs.map(f => f.lastModifiedTime).max)
    }

  // TODO: not correct in circle-ci after case restore
  private def outputFilesOutdated: Boolean = (for {
    s <- lastModified(sourceFiles)
    o <- lastModified(if (pkgDir.exists) pkgDir.list.toList else Nil)
  } yield o.isBefore(s)).getOrElse(true)

  def run(forceRegeneration: Boolean = false): IO[List[JFile]] =
    (if ((forceRegeneration || (!pkgDir.exists() || outputFilesOutdated))) {
       (for {
         _     <- IO.whenA(!pkgDir.exists())(IO.println("Generated source not found"))
         _     <- IO.whenA(outputFilesOutdated)(IO.println("Generated source is outdated"))
         _     <- IO.println("Generating Postgres models")
         _     <- rmDocker
         _     <- startDocker
         files <- generatorTask
         _     <- rmDocker
       } yield files).onError(_ => rmDocker)
     } else {
       IO(pkgDir.list.toList)
     })
      .map(_.map(_.toJava))

  def unsafeRunSync(forceRegeneration: Boolean = false): Seq[JFile] = {
    import cats.effect.unsafe.implicits.global
    run(forceRegeneration).unsafeRunSync()
  }
}

object PgCodeGen {
  type TableName   = String
  type TableMap[T] = Map[TableName, List[T]]
  type Enums       = List[Enum]
  type ScalaType   = String
  type Result[T]   = Either[String, T]

  final case class Enum(name: String, values: List[EnumValue]) {
    val scalaName: String = toScalaName(name).capitalize
  }
  final case class EnumValue(name: String) {
    val scalaName: String = toScalaName(name).capitalize
  }

  final case class Column(
    columnName: String,
    scalaType: ScalaType,
    pgType: Type,
    isEnum: Boolean,
    isNullable: Boolean,
    default: Option[ColumnDefault],
  ) {
    val scalaName: String = toScalaName(columnName)

    val codecName: String =
      ((if (isEnum) s"${toScalaName(pgType.name).capitalize}.codec" else pgType.name) + (if (isNullable) ".opt"
                                                                                         else ""))
  }

  final case class ColumnRef(fromColName: String, toColName: String, toTableName: String)

  sealed trait ColumnDefault
  object ColumnDefault {
    case object AutoInc extends ColumnDefault

    def fromString(value: String): Option[ColumnDefault] =
      if (value.contains("nextval")) Some(AutoInc) else None
  }
  sealed trait Constraint {
    def name: String
  }
  sealed trait UniqueConstraint extends Constraint {
    def columnNames: List[String]
  }
  object Constraint {
    final case class PrimaryKey(name: String, columnNames: List[String]) extends UniqueConstraint
    final case class Unique(name: String, columnNames: List[String])     extends UniqueConstraint
    final case class ForeignKey(name: String, refs: List[ColumnRef])     extends Constraint
    final case class Unknown(name: String)                               extends Constraint
  }

  final case class Index(name: String, createSql: String)

  final case class Table(
    name: String,
    columns: List[Column],
    autoIncColumns: List[Column],
    constraints: List[Constraint],
    indexes: List[Index],
    autoIncFk: List[Column],
  ) {
    val tableClassName: String = toTableClassName(name)
    val rowClassName: String   = toRowClassName(name)
  }

  final case class ConstraintRow(
    tableName: String,
    name: String,
    typ: String,
    refCol: String,
    refTable: String,
    fromCol: String,
  )

  def toScalaName(s: String): String =
    toCamelCase(s) match {
      case "type"   => "`type`"
      case "import" => "`import`"
      case "val"    => "`val`" // add more as required
      case v        => v
    }

  def toCamelCase(s: String, capitalize: Boolean = false): String =
    s.split("_")
      .zipWithIndex
      .map {
        case (t, 0) if !capitalize => t
        case (t, _)                => t.capitalize
      }
      .mkString

  private def toRowClassName(s: String): String =
    toCamelCase(s, capitalize = true) + "Row"

  private def toTableClassName(s: String): String =
    toCamelCase(s, capitalize = true) + "Table"
}
