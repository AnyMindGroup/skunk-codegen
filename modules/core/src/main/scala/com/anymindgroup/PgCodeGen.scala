package com.anymindgroup

import better.files.*
import cats.Show
import cats.data.{NonEmptyList, Validated}
import cats.effect.*
import cats.implicits.*
import com.anymindgroup.PgCodeGen.Constraint.PrimaryKey
import dumbo.{ConnectionConfig, Dumbo, ResourceFile}
import fs2.io.file.Files
import natchez.Trace.Implicits.noop
import skunk.*
import skunk.codec.all.*
import skunk.data.Type
import skunk.implicits.*

import java.io.File as JFile
import java.nio.charset.Charset
import scala.concurrent.duration.*
import scala.sys.process.*

class PgCodeGen(
  host: String,
  user: String,
  database: String,
  operateDatabase: Option[String],
  port: Int,
  password: Option[String],
  useDockerImage: Option[String],
  outputDir: JFile,
  pkgName: String,
  sourceDir: JFile,
  excludeTables: List[String],
  scalaVersion: String,
) {
  import PgCodeGen.*

  private val pkgDir                 = File(outputDir.toPath(), pkgName.replace('.', JFile.separatorChar))
  private val schemaHistoryTableName = "dumbo_history"
  implicit val consoleErrLevel: std.Console[IO] = new std.Console[IO] {
    override def readLineWithCharset(charset: Charset): IO[String] = IO.consoleForIO.readLineWithCharset(charset)
    override def print[A](a: A)(implicit S: Show[A]): IO[Unit]     = IO.unit
    override def println[A](a: A)(implicit S: Show[A]): IO[Unit]   = IO.unit
    override def error[A](a: A)(implicit S: Show[A]): IO[Unit]     = IO.consoleForIO.error(a)
    override def errorln[A](a: A)(implicit S: Show[A]): IO[Unit]   = IO.consoleForIO.errorln(a)
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

  private def toType(
    udt: String,
    maxCharLength: Option[Int],
    numPrecision: Option[Int],
    numScale: Option[Int],
  ): Type =
    (udt, maxCharLength, numPrecision, numScale) match {
      case (u @ ("bpchar" | "varchar"), Some(l), _, _) => Type(s"$u($l)")
      case ("numeric", _, Some(p), Some(s))            => Type(s"numeric($p${if (s > 0) ", " + s.toString else ""})")
      case _ =>
        val componentTypes = if (udt.startsWith("_")) List(Type(udt.stripPrefix("_"))) else Nil
        Type(udt, componentTypes)
    }

  private def getColumns(s: Session[IO], enums: Enums): IO[TableMap[Column]] = {
    val filterFragment: Fragment[Void] =
      sql" AND table_name NOT IN (#${(schemaHistoryTableName :: excludeTables).mkString("'", "','", "'")})"

    val q =
      sql"""SELECT table_name,column_name,udt_name,character_maximum_length,numeric_precision,numeric_scale,is_nullable,column_default,is_generated
                  FROM information_schema.COLUMNS WHERE table_schema = 'public'$filterFragment UNION
                  (SELECT
          cls.relname AS table_name,
          attr.attname AS column_name,
          tp.typname AS udt_name,
          information_schema._pg_char_max_length(information_schema._pg_truetypid(attr.*, tp.*), information_schema._pg_truetypmod(
          attr.*, tp.*))::information_schema.cardinal_number AS character_maximum_length,
          information_schema._pg_numeric_precision(information_schema._pg_truetypid(attr.*, tp.*), information_schema._pg_truetypmod(
          attr.*, tp.*))::information_schema.cardinal_number AS numeric_precision,
          information_schema._pg_numeric_scale(information_schema._pg_truetypid(attr.*, tp.*), information_schema._pg_truetypmod(
          attr.*, tp.*))::information_schema.cardinal_number AS numeric_scale,
          CASE
              WHEN attr.attnotnull OR tp.typtype = 'd'::"char" AND tp.typnotnull THEN 'NO'::text
              ELSE 'YES'::text
          END::information_schema.yes_or_no AS is_nullable,
          NULL AS column_default,
          'NEVER' AS is_generated
          FROM pg_catalog.pg_attribute as attr
          JOIN pg_catalog.pg_class as cls on cls.oid = attr.attrelid
          JOIN pg_catalog.pg_namespace as ns on ns.oid = cls.relnamespace
          JOIN pg_catalog.pg_type as tp on tp.oid = attr.atttypid
          WHERE cls.relkind = 'm' and attr.attnum >= 1 AND ns.nspname = 'public'
          ORDER by attr.attnum)
            """.query(name ~ name ~ name ~ int4.opt ~ int4.opt ~ int4.opt ~ varchar(3) ~ varchar.opt ~ varchar)

    s.execute(q.map {
      case tName ~ colName ~ udt ~ maxCharLength ~ numPrecision ~ numScale ~ nullable ~ default ~ is_generated =>
        (
          tName,
          colName,
          toType(udt, maxCharLength, numPrecision, numScale),
          nullable == "YES",
          default.flatMap(ColumnDefault.fromString),
          is_generated == "ALWAYS",
        )
    }).map(_.map { case (tName, colName, udt, isNullable, default, isAlwaysGenerated) =>
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
            isAlwaysGenerated = isAlwaysGenerated,
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
      _.groupBy(_._1).toList.map { case (name, values) =>
        Enum(name, values.map(_._2).map(EnumValue(_)))
      }
    }
  }

  private def getViews(s: Session[IO]): IO[Set[TableName]] = {
    val q: Query[Void, String] =
      sql"""SELECT table_name FROM information_schema.VIEWS WHERE table_schema = 'public'
      UNION
      SELECT matviewname FROM pg_matviews WHERE schemaname = 'public';""".query(name)

    s.execute(q).map(_.toSet)
  }

  private val postgresDBSingleSession = Session
    .single[IO](
      host = host,
      port = port,
      user = user,
      database = database,
      password = password,
    )

  private val singleSession = Session
    .single[IO](
      host = host,
      port = port,
      user = user,
      database = operateDatabase.getOrElse(database),
      password = password,
    )

  private val dumboWithFiles = Dumbo.withFilesIn[IO](fs2.io.file.Path.fromNioPath(sourceDir.toPath()))

  private val dumbo = dumboWithFiles.apply(
    connection = ConnectionConfig(
      host = host,
      user = user,
      database = operateDatabase.getOrElse(database),
      port = port,
      password = password,
    ),
    defaultSchema = "public",
    schemaHistoryTable = schemaHistoryTableName,
  )

  private def listMigrationFiles: IO[List[ResourceFile]] = dumboWithFiles.listMigrationFiles.flatMap {
    case Validated.Invalid(errs) =>
      IO.raiseError(
        new Throwable(
          s"Failed reading source files:\n${errs.toList.map(_.getMessage()).mkString("\n")}"
        )
      )
    case Validated.Valid(files) => IO.pure(files)
  }

  private def generatorTask: IO[List[File]] =
    postgresDBSingleSession.use { s =>
      operateDatabase match {
        case Some(opDBName) =>
          for {
            result <- s.execute(sql"SELECT true FROM pg_database WHERE datname = ${varchar}".query(bool))(opDBName)
            _      <- IO.whenA(result.isEmpty)(s.execute(sql"CREATE DATABASE #${opDBName};".command).as(()))
          } yield ()
        case None => IO.unit
      }
    } >> singleSession.use { s =>
      for {
        _     <- s.execute(sql"DROP SCHEMA public CASCADE;".command)
        _     <- s.execute(sql"CREATE SCHEMA public;".command)
        _     <- dumbo.runMigration.void
        enums <- getEnums(s)
        (((columns, indexes), constraints), views) <-
          getColumns(s, enums).parProduct(getIndexes(s)).parProduct(getConstraints(s)).parProduct(getViews(s))
        tables = toTables(columns, indexes, constraints, views)
        files = pkgFiles(tables, enums) ::: tables.flatMap { table =>
                  rowFileContent(table) match {
                    case None => Nil
                    case Some(rowContent) =>
                      List(
                        pkgDir / s"${table.tableClassName}.scala" -> tableFileContent(table),
                        pkgDir / s"${table.rowClassName}.scala"   -> rowContent,
                      )
                  }
                }
        _ <- IO(pkgDir.delete(true).createDirectoryIfNotExists())
        res <- files.parTraverse { case (file, content) =>
                 for {
                   _ <- IO(file.writeText(content))
                   _ <- IO.println(s"Created ${file.pathAsString}")
                 } yield file
               }
      } yield res
    }

  private val pgServiceName = pkgName.replace('.', '-')

  private def awaitReadiness: IO[Unit] =
    fs2.Stream
      .repeatEval(postgresDBSingleSession.use(_.unique(sql"SELECT 1".query(int4)).void).attempt.map(_.swap.toOption))
      .metered(500.millis)
      .timeout(10.seconds)
      .unNoneTerminate
      .compile
      .drain
      .onError(e => IO.println(s"Could not connect to docker on $host:$port ${e.getMessage()}"))

  private def startDocker: IO[Unit] =
    useDockerImage match {
      case None => IO.unit
      case Some(image) =>
        val cmd =
          s"docker run -p $port:5432 -h $host -e POSTGRES_USER=$user ${password.fold("")(p => s"-e POSTGRES_PASSWORD=$p ")}" +
            s"--name $pgServiceName -d $image"
        IO(cmd.!!) >> awaitReadiness
    }

  private def rmDocker: IO[Unit] = if (useDockerImage.nonEmpty) {
    IO(s"docker rm -f $pgServiceName" ! ProcessLogger(_ => ())).void
  } else IO.unit

  private def toTables(
    columns: TableMap[Column],
    indexes: TableMap[Index],
    constraints: TableMap[Constraint],
    views: Set[TableName],
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
      val generatedCols    = findAutoIncColumns(tname) ::: tableCols.filter(_.isAlwaysGenerated)
      val autoIncFk = tableConstraints.collect { case c: Constraint.ForeignKey => c }.flatMap {
        _.refs.flatMap { ref =>
          tableCols.find(c => c.columnName == ref.fromColName).filter { _ =>
            findAutoPk(ref.toTableName).exists(_.columnName == ref.toColName)
          }
        }
      }

      Table(
        name = tname,
        columns = tableCols.filterNot((generatedCols ::: autoIncFk).contains),
        generatedColumns = generatedCols,
        constraints = tableConstraints,
        indexes = indexes.getOrElse(tname, Nil),
        autoIncFk = autoIncFk,
        isView = views.contains(tname),
      )
    }
  }

  private def scalaEnums(enums: Enums): List[(File, String)] =
    enums.map { e =>
      (
        pkgDir / s"${e.scalaName}.scala",
        if (!scalaVersion.startsWith("3")) {
          s"""|package $pkgName
              |
              |import skunk.Codec
              |import enumeratum.values.{StringEnumEntry, StringEnum}
              |import skunk.data.Type
              |
              |sealed abstract class ${e.scalaName}(val value: String) extends StringEnumEntry
              |object ${e.scalaName} extends StringEnum[${e.scalaName}] {
              |  ${e.values
               .map(v => s"""case object ${v.scalaName} extends ${e.scalaName}("${v.name}")""")
               .mkString("\n  ")}
              |
              |  val values: IndexedSeq[${e.scalaName}] = findValues
              |
              |  implicit val codec: Codec[${e.scalaName}] =
              |    Codec.simple[${e.scalaName}](
              |      a => a.value,
              |      s => withValueEither(s).left.map(_.getMessage()),
              |      Type("${e.name}"),
              |    )
              |}""".stripMargin
        } else {
          s"""|package $pkgName
              |
              |import skunk.Codec
              |import skunk.data.Type
              |
              |enum ${e.scalaName}(val value: String):
              |  ${e.values.map(v => s"""case ${v.scalaName} extends ${e.scalaName}("${v.name}")""").mkString("\n  ")}
              |
              |object ${e.scalaName}:
              |  implicit val codec: Codec[${e.scalaName}] =
              |    Codec.simple[${e.scalaName}](
              |      a => a.value,
              |      s =>${e.scalaName}.values.find(_.value == s).toRight(s"Invalid ${e.name} type: $$s"),
              |      Type("${e.name}"),
              |    )""".stripMargin
        },
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

    val arrayCodec =
      s"""|  implicit class ListCodec[A](arrCodec: skunk.Codec[skunk.data.Arr[A]]) {
          |    def _list(implicit factory: scala.collection.compat.Factory[A, List[A]]): skunk.Codec[List[A]] = {
          |      arrCodec.imap(arr => arr.flattenTo(factory))(xs => skunk.data.Arr.fromFoldable(xs))
          |    }
          |  }""".stripMargin
    val pkgLastPart = pkgName.split('.').last
    List(
      (
        pkgDir / "package.scala",
        s"""|package ${pkgName.split('.').dropRight(1).mkString(".")}
            |
            |package object ${pkgLastPart} {
            |
            |$arrayCodec
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
            |final case class Constraint(name: String)
           """.stripMargin,
      ),
      (
        pkgDir / "Cols.scala",
        s"""|package $pkgName
            |import skunk.*
            |import cats.data.NonEmptyList
            |import cats.implicits.*
            |
            |final case class Cols[A] private[$pkgLastPart] (names: NonEmptyList[String], codec: Codec[A], tableAlias: String)
            |    extends (A => AppliedCol[A]) {
            |  def name: String                      = names.intercalate(",")
            |  def fullName: String                  = names.map(n => s"$${tableAlias}.$$n").intercalate(",")
            |  def aliasedName: String               = names.map(name => s"$${tableAlias}.$${name} $${tableAlias}__$$name").intercalate(",")
            |  def ~[B](that: Cols[B]): Cols[(A, B)] = Cols(this.names ::: that.names, this.codec ~ that.codec, this.tableAlias)
            |  def apply(a: A): AppliedCol[A]        = AppliedCol(this, a)
            |}
            |
            |final case class AppliedCol[A] (cols: Cols[A], value: A) {
            |  def name     = cols.name
            |  def fullName = cols.fullName
            |  def codec    = cols.codec
            |
            |  def ~[B] (that: AppliedCol[B]): AppliedCol[(A, B)] = AppliedCol(this.cols ~ that.cols, (this.value, that.value))
            |}
            |""".stripMargin,
      ),
    ) ::: scalaEnums(enums)
  }

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
          // check by type name without a max length parameter if set, e.g. vacrhar instead of varchar(3)
          case (scalaType, pgTypes) if pgTypes.map(_.name).contains(t.name.takeWhile(_ != '(')) =>
            if (isNullable) s"Option[$scalaType]" else scalaType
        }.orElse {
          enums.find(_.name == t.name).map(e => if (isNullable) s"Option[${e.scalaName}]" else e.scalaName)
        }.toRight(s"No scala type found for type ${t.name}")
      case x :: Nil =>
        toScalaType(x, isNullable = false, enums).map(t => if (isNullable) s"Option[List[$t]]" else s"List[$t]")
      case x :: xs =>
        Left(s"Unsupported type of multiple components: ${x :: xs}")
    }

  private def rowFileContent(table: Table): Option[String] = {
    import table.*

    def toClassPropsStr(cols: List[Column]) = cols
      .map(c => s"    ${c.scalaName}: ${c.scalaType}")
      .mkString("", ",\n", "")

    def toUpdateClassPropsStr(cols: List[Column]) = cols
      .map(c => s"    ${c.scalaName}: Option[${c.scalaType}]")
      .mkString("", ",\n", "")

    def toCodecFieldsStr(cols: List[Column]) = s"${cols.map(_.codecName).mkString(" *: ")}"

    def toUpdateFragment(cols: List[Column]) = {
      def toOptFrStr(c: Column) = s"""${c.scalaName}.map(sql"${c.columnName}=$${${c.codecName}}".apply(_))"""
      s"""
        def fragment: AppliedFragment = List(
          ${cols.map(toOptFrStr(_)).mkString(",\n")}
        ).flatten.intercalate(void",")
      """
    }

    val rowUpdateClassData =
      if (table.isView) (Nil, Nil)
      else
        primaryUniqueConstraint match {
          case Some(cstr) =>
            columns.filterNot(cstr.containsColumn) match {
              case Nil => (Nil, Nil)
              case updateCols =>
                val colsData     = toUpdateClassPropsStr(updateCols)
                val fragmentData = toUpdateFragment(updateCols)
                (
                  updateCols,
                  List(
                    "",
                    s"final case class $rowUpdateClassName(",
                    s"$colsData",
                    ") {",
                    fragmentData,
                    "}",
                  ),
                )
            }

          case None => (Nil, Nil)
        }

    def withImportsStr = rowUpdateClassData match {
      case (Nil, Nil) => ""
      case (_, _)     => List("import skunk.implicits.*", "import cats.implicits.*").mkString("\n")
    }

    def withUpdateStr = rowUpdateClassData match {
      case (Nil, Nil) => ""
      case (cols, _) =>
        val updateProps = cols.map(_.scalaName).map(n => s"$n = Some($n)").mkString("    ", ",\n    ", "")
        List(
          " {",
          s"  def asUpdate: $rowUpdateClassName = $rowUpdateClassName(",
          s"$updateProps",
          "  )",
          "",
          s"  def withUpdateAll: ($rowClassName, AppliedFragment) = (this, asUpdate.fragment)",
          s"  def withUpdate(f: $rowUpdateClassName => $rowUpdateClassName): ($rowClassName, AppliedFragment) = (this, f(asUpdate).fragment)",
          "",
          "}",
        ).mkString("\n")
    }

    columns.headOption.map { _ =>
      val colsData  = toClassPropsStr(columns)
      val codecData = toCodecFieldsStr(columns)
      List(
        s"package $pkgName",
        "",
        "import skunk.*",
        withImportsStr,
        "",
        s"final case class $rowClassName(",
        s"$colsData",
        s")$withUpdateStr",
        "",
        s"object $rowClassName {",
        s"  implicit val codec: Codec[$rowClassName] = ($codecData).to[$rowClassName]",
        "}",
        s"${rowUpdateClassData._2.mkString("\n")}",
      ).mkString("\n")
    }
  }

  private def tableFileContent(table: Table): String = {
    val (maybeAllCol, cols) = tableColumns(table)
    (
      List(
        s"package $pkgName\n",
        "import skunk.*",
        "import skunk.implicits.*",
        "import cats.data.NonEmptyList",
      ) :::
        List(
          "",
          s"class ${table.tableClassName}(val tableName: String) {",
          s"  def withPrefix(prefix: String): ${table.tableClassName} = new ${table.tableClassName}(prefix + tableName)",
          s"  def withAlias(alias: String): ${table.tableClassName}   = new ${table.tableClassName}(alias)",
          "",
          maybeAllCol.getOrElse(""),
          "  object column {",
          s"$cols",
          "  }",
          writeStatements(table),
          selectAllStatement(table),
          "}",
          "",
          s"""object ${table.tableClassName} extends ${table.tableClassName}("${table.name}")""",
        )
    ).mkString("\n")
  }

  private def queryTypesStr(table: Table): (String, String) = {
    import table.*

    if (autoIncFk.isEmpty) {
      (rowClassName, s"${rowClassName}.codec")
    } else {
      val autoIncFkCodecs     = autoIncFk.map(_.codecName).mkString(" *: ")
      val autoIncFkScalaTypes = autoIncFk.map(_.scalaType).mkString(" *: ")
      (s"($autoIncFkScalaTypes ~ $rowClassName)", s"$autoIncFkCodecs ~ ${rowClassName}.codec")
    }
  }

  private def writeStatements(table: Table): String =
    if (table.isView) ""
    else {
      import table.*

      val allCols                        = autoIncFk ::: columns
      val allColNames                    = allCols.map(_.columnName).mkString(",")
      val (insertScalaType, insertCodec) = queryTypesStr(table)

      val returningStatement = generatedColumns match {
        case Nil => ""
        case _   => generatedColumns.map(_.columnName).mkString(" RETURNING ", ",", "")
      }
      val returningType = generatedColumns
        .map(_.scalaType)
        .mkString("", " *: ", if (generatedColumns.length > 1) " *: EmptyTuple" else "")
      val fragmentType = generatedColumns match {
        case Nil => "command"
        case _   => s"query(${generatedColumns.map(_.codecName).mkString(" *: ")})"
      }

      val upsertQ = primaryUniqueConstraint.map { cstr =>
        val queryType = generatedColumns match {
          case Nil => s"Command[$insertScalaType *: updateFr.A *: EmptyTuple]"
          case _   => s"Query[$insertScalaType *: updateFr.A *: EmptyTuple, $returningType]"
        }

        s"""|  def upsertQuery(updateFr: AppliedFragment): $queryType =
            |    sql\"\"\"INSERT INTO #$$tableName ($allColNames) VALUES ($${$insertCodec})
            |          ON CONFLICT ON CONSTRAINT ${cstr.name}
            |          DO UPDATE SET $${updateFr.fragment}$returningStatement\"\"\".$fragmentType""".stripMargin
      }

      val queryType = generatedColumns match {
        case Nil => s"Command[$insertScalaType]"
        case _   => s"Query[$insertScalaType, $returningType]"
      }
      val insertQ =
        s"""|  def insertQuery(ignoreConflict: Boolean = true): $queryType = {
            |    val onConflictFr = if (ignoreConflict) const" ON CONFLICT DO NOTHING" else const""
            |    sql\"INSERT INTO #$$tableName ($allColNames) VALUES ($${$insertCodec})$$onConflictFr$returningStatement\".$fragmentType
            |  }""".stripMargin

      val insertCol =
        s"""|
            |  def insert[A](cols: Cols[A]): Command[A] =
            |    sql\"INSERT INTO #$$tableName (#$${cols.name}) VALUES ($${cols.codec})\".command
            |
            |  def insert0[A, B](cols: Cols[A], rest: Fragment[B] = sql"ON CONFLICT DO NOTHING")(implicit
            |    ev: Void =:= B
            |  ): Command[A] =
            |    (sql\"INSERT INTO #$$tableName (#$${cols.name}) VALUES ($${cols.codec}) " ~ rest).command.contramap[A](a => (a, ev.apply(Void)))
            |
            |  def insert[A, B](cols: Cols[A], rest: Fragment[B] = sql"ON CONFLICT DO NOTHING"): Command[(A, B)] =
            |    (sql\"INSERT INTO #$$tableName (#$${cols.name}) VALUES ($${cols.codec})" ~ rest).command
            |""".stripMargin
      List(
        upsertQ.getOrElse(""),
        insertQ,
        insertCol,
      ).mkString("\n\n")
    }

  private def tableColumns(table: Table): (Option[String], String) = {
    val allCols = table.generatedColumns ::: table.autoIncFk ::: table.columns
    val cols =
      allCols.map(column =>
        s"""    val ${column.snakeCaseScalaName} = Cols(NonEmptyList.of("${column.columnName}"), ${column.codecName}, tableName)"""
      )

    val allCol = NonEmptyList
      .fromList(table.columns.map(_.columnName))
      .map { xs =>
        val s = xs.map(x => s""""$x"""").intercalate(",")
        s"""|
            |  val all = Cols(NonEmptyList.of($s), ${table.rowClassName}.codec, tableName)
            |""".stripMargin
      }

    allCol -> cols.mkString("\n")
  }

  private def selectAllStatement(table: Table): String = {
    import table.*

    val generatedColStm = if (generatedColumns.nonEmpty) {
      val types       = generatedColumns.map(_.codecName).mkString(" *: ")
      val sTypes      = generatedColumns.map(_.scalaType).mkString(" *: ")
      val colNamesStr = (generatedColumns ::: columns).map(_.columnName).mkString(", ")

      s"""
         |  def selectAllWithGenerated[A](addClause: Fragment[A] = Fragment.empty): Query[A, $sTypes *: $rowClassName *: EmptyTuple] =
         |    sql"SELECT $colNamesStr FROM #$$tableName $$addClause".query($types *: ${rowClassName}.codec)
         |
         """.stripMargin
    } else {
      ""
    }

    val colNamesStr                   = (autoIncFk ::: columns).map(_.columnName).mkString(",")
    val (queryReturnType, queryCodec) = queryTypesStr(table)

    val defaultStm = s"""
                        |  def selectAll[A](addClause: Fragment[A] = Fragment.empty): Query[A, $queryReturnType] =
                        |    sql"SELECT $colNamesStr FROM #$$tableName $$addClause".query($queryCodec)
                        |
                        |""".stripMargin

    val selectCol = s"""|  def select[A, B](cols: Cols[A], rest: Fragment[B] = Fragment.empty): Query[B, A] =
                        |    sql"SELECT #$${cols.name} FROM #$$tableName $$rest".query(cols.codec)
                        |""".stripMargin
    generatedColStm ++ defaultStm ++ selectCol
  }

  private def lastModified(modified: List[Long]): Option[Long] =
    modified.sorted(Ordering[Long].reverse).headOption

  private def outputFilesOutdated(sourcesModified: List[Long]): Boolean = (for {
    s       <- lastModified(sourcesModified)
    outFiles = if (pkgDir.exists) pkgDir.list.toList else Nil
    o       <- lastModified(outFiles.map(_.lastModifiedTime.getEpochSecond()))
    // can't rely on timestamps when running in CI
    isNotCI = sys.env.get("CI").isEmpty
  } yield isNotCI && o < s).getOrElse(true)

  def run(forceRegeneration: Boolean = false): IO[List[JFile]] =
    listMigrationFiles.flatMap { sourceFiles =>
      sourceFiles
        .map(_.path)
        .traverse(Files[IO].getLastModifiedTime(_))
        .map(l => outputFilesOutdated(l.map(_.toSeconds)))
        .map((sourceFiles, _))
    }.flatMap { case (sourceFiles, isOutdated) =>
      (if ((forceRegeneration || (!pkgDir.exists() || isOutdated))) {
         (for {
           _     <- IO.raiseWhen(sourceFiles.isEmpty)(new Exception(s"Cannot find any .sql files in ${sourceDir.toPath()}"))
           _     <- IO.whenA(!pkgDir.exists())(IO.println("Generated source not found"))
           _     <- IO.whenA(pkgDir.exists() && isOutdated)(IO.println("Generated source is outdated"))
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
    }

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
    val scalaName: String = toScalaName(name.toLowerCase).capitalize
  }

  final case class Column(
    columnName: String,
    scalaType: ScalaType,
    pgType: Type,
    isEnum: Boolean,
    isNullable: Boolean,
    default: Option[ColumnDefault],
    isAlwaysGenerated: Boolean,
  ) {
    val scalaName: String          = toScalaName(columnName)
    val snakeCaseScalaName: String = escapeScalaKeywords(columnName)

    def isArr = pgType.componentTypes.nonEmpty

    val codecName: String =
      (
        (if (isEnum) s"${toScalaName(pgType.name).capitalize}.codec" else s"skunk.codec.all.${pgType.name}") +
          (if (isArr) "._list" else "") +
          (if (isNullable) ".opt" else "")
      )
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

    def containsColumn(c: Column): Boolean = columnNames.contains(c.columnName)
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
    generatedColumns: List[Column],
    constraints: List[Constraint],
    indexes: List[Index],
    autoIncFk: List[Column],
    isView: Boolean,
  ) {
    val tableClassName: String     = toTableClassName(name)
    val rowClassName: String       = toRowClassName(name)
    val rowUpdateClassName: String = toRowUpdateClassName(name)

    val primaryUniqueConstraint: Option[UniqueConstraint] = constraints.collectFirst { case c: PrimaryKey =>
      c
    }.orElse {
      constraints.collectFirst { case c: Constraint.Unique =>
        c
      }
    }

    def isInPrimaryConstraint(c: Column): Boolean = primaryUniqueConstraint.exists(_.containsColumn(c))
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
    escapeScalaKeywords(toCamelCase(s))

  def escapeScalaKeywords(v: String): String =
    v match {
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

  private def toRowUpdateClassName(s: String): String =
    toCamelCase(s, capitalize = true) + "Update"

  private def toTableClassName(s: String): String =
    toCamelCase(s, capitalize = true) + "Table"
}
