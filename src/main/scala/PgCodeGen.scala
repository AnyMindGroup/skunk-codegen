//> using scala 3.7.1
//> using dep com.indoorvivants.roach::core::0.1.0
//> using platform native
//> using nativeVersion 0.5.8

package com.anymindgroup

import roach.*
import scala.util.Using
import scala.scalanative.unsafe.Zone

import roach.codecs.*

import java.io.File
import java.nio.charset.Charset
import scala.concurrent.duration.*
import scala.sys.process.*
import java.nio.file.Paths
import java.nio.file.Path
import java.nio.file.Files
import scala.concurrent.Future
import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.util.Failure
import scala.util.Success
import scala.concurrent.Await

@main
def run(args: String*) =
  given ExecutionContext = ExecutionContext.global

  val argsMap = args.toList
    .flatMap(_.split('=').map(_.trim().toLowerCase()))
    .sliding(2, 2)
    .collect { case a :: b :: _ => a -> b }
    .toMap

  (for
    host           <- argsMap.get("-host").toRight("host not set")
    user           <- argsMap.get("-user").toRight("user not set")
    database       <- argsMap.get("-database").toRight("database not set")
    operateDatabase = argsMap.get("-operate-database")
    port           <- argsMap.get("-port").flatMap(_.toIntOption).toRight("missing or invalid port")
    password        = argsMap.get("-password")
    useDockerImage  = argsMap.get("-use-docker-image")
    outputDir      <- argsMap.get("-output-dir").map(File(_)).toRight("outputDir not set")
    pkgName        <- argsMap.get("-pkg-name").toRight("pkgName not set")
    sourceDir      <- argsMap.get("-source-dir").map(File(_)).toRight("sourceDir not set")
    excludeTables   = argsMap.get("-exclude-tables").toList.flatMap(_.split(","))
    scalaVersion    = argsMap.get("-scala-version").getOrElse("3.7.1")
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

extension (p: Path) def /(s: String): Path = Paths.get(p.toString(), s)

case class Type(name: String, componentTypes: List[Type] = Nil)

class PgCodeGen(
  host: String,
  user: String,
  database: String,
  operateDatabase: Option[String],
  port: Int,
  password: Option[String],
  useDockerImage: Option[String],
  outputDir: File,
  pkgName: String,
  sourceDir: File,
  excludeTables: List[String],
  scalaVersion: String,
)(using ExecutionContext) {
  import PgCodeGen.*

  private val connectionString       = s"postgresql://$user${password.map(p => s":$p").getOrElse("")}@$host:$port/$database"
  private val pkgDir                 = Paths.get(outputDir.getPath(), pkgName.replace('.', File.separatorChar))
  private val schemaHistoryTableName = "dumbo_history"

  private def getConstraints =
    pgSessionRun:
      val q =
        sql"""SELECT c.table_name, c.constraint_name, c.constraint_type, cu.column_name, cu.table_name, kcu.column_name
              FROM information_schema.table_constraints AS c
              JOIN information_schema.key_column_usage as kcu ON kcu.constraint_name = c.constraint_name
              JOIN information_schema.constraint_column_usage AS cu ON cu.constraint_name = c.constraint_name
              WHERE c.table_schema='public'""".all(name ~ name ~ varchar ~ name ~ name ~ name)

      q.map { (a, b, c, d, e, f) =>
        ConstraintRow(tableName = a, name = b, typ = c, refCol = d, refTable = e, fromCol = f)
      }.groupBy(_.tableName).map { case (tName, constraints) =>
        (
          tName,
          constraints.groupBy(c => (c.name, c.typ)).toVector.map {
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

  private def pgSessionRun[A](f: (Zone, Database) ?=> A): Future[A] =
    Future:
      Zone:
        Pool.single(connectionString): pool =>
          pool.withLease(f)

  private def getColumns(enums: Enums) =
    pgSessionRun:
      val filterFragment =
        s" AND table_name NOT IN (${(schemaHistoryTableName :: excludeTables).mkString("'", "','", "'")})"

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
              """.all(name ~ name ~ name ~ int4.opt ~ int4.opt ~ int4.opt ~ varchar ~ varchar.opt ~ varchar)

      q.map { (tName, colName, udt, maxCharLength, numPrecision, numScale, nullable, default, is_generated) =>
        (
          tName,
          colName,
          toType(udt, maxCharLength, numPrecision, numScale),
          nullable == "YES",
          default.flatMap(ColumnDefault.fromString),
          is_generated == "ALWAYS",
        )
      }.map { (tName, colName, udt, isNullable, default, isAlwaysGenerated) =>
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
        } match {
          case Left(err)    => throw Throwable(err)
          case Right(value) => value
        }
      }.groupBy(_._1).map { case (k, v) => (k, v.map(_._2)) }
  end getColumns

  private def getIndexes =
    pgSessionRun:
      val q =
        sql"""SELECT indexname,indexdef,tablename FROM pg_indexes WHERE schemaname='public'""".all(name ~ text ~ name)

      q.map { (name, indexDef, tableName) =>
        (tableName, Index(name, indexDef))
      }.groupBy(_._1).map((tName, v) => (tName, v.map(_._2)))

  private def getEnums =
    pgSessionRun:
      val q =
        sql"""SELECT pt.typname,pe.enumlabel FROM pg_enum AS pe JOIN pg_type AS pt ON pt.oid = pe.enumtypid"""
          .all(
            name ~ name
          )

      q.groupBy(_._1).toVector.map { (name, values) =>
        Enum(name, values.map(_._2).map(EnumValue(_)))
      }

  private def getViews: Future[Set[TableName]] =
    pgSessionRun:
      sql"""SELECT table_name FROM information_schema.VIEWS WHERE table_schema = 'public'
        UNION
        SELECT matviewname FROM pg_matviews WHERE schemaname = 'public';""".all(name).toSet

  private def listMigrationFiles = Future(sourceDir.listFiles().toList)

  private def generatorTask =
    for
      // _ <- pgSessionRun {
      //        operateDatabase match {
      //          case Some(opDBName) =>
      //            val result = sql"SELECT true FROM pg_database WHERE datname = ${varchar}".one(opDBName, bool)
      //            if result.contains(true) then () else sql"CREATE DATABASE #${opDBName}".exec()
      //          case None => ()
      //        }

      //        sql"DROP SCHEMA public CASCADE".exec()
      //        sql"CREATE SCHEMA public".exec()
      //      }
      _ <- Future(println("Running migration..."))
      _ = {
        // println(s"Run migration with sql in ${sourceDir.getPath()}")

        val cmd = List(
          """docker run --net="host"""",
          s"-v ${sourceDir.getAbsolutePath()}:/migration",
          "rolang/dumbo:latest-alpine",
          s"-user=$user",
          password.map(p => s" -password=$p").getOrElse(""),
          s"-url=postgresql://$host:$port/$database",
          "-location=/migration",
          "migrate",
        ).mkString(" ")

        println(cmd)

        cmd ! (ProcessLogger(out => println(out), err => Console.err.println(err))) match
          case 0       => ()
          case nonZero => throw Throwable(s"Migration exited with non zero code: $nonZero")
      }
      _                                           = println("Migration complete...")
      enums                                      <- getEnums
      (((columns, indexes), constraints), views) <- getColumns(enums).zip(getIndexes).zip(getConstraints).zip(getViews)
      tables                                      = toTables(columns, indexes, constraints, views)
      filesToWrite = pkgFiles(tables, enums) ::: tables.flatMap { table =>
                       rowFileContent(table) match {
                         case None => Nil
                         case Some(rowContent) =>
                           List(
                             pkgDir / s"${table.tableClassName}.scala" -> tableFileContent(table),
                             pkgDir / s"${table.rowClassName}.scala"   -> rowContent,
                           )
                       }
                     }
      _ <- Future {
             Files.deleteIfExists(pkgDir)
             Files.createDirectories(pkgDir)
           }
      files <- Future.traverse(filesToWrite): (path, content) =>
                 Future:
                   Files.writeString(path, content)
                   println(s"Created ${path.toString()}")
                   File(path.toString())
    yield files
  end generatorTask

  private val pgServiceName = pkgName.replace('.', '-')

  private def awaitReadiness: Future[Unit] =
    @tailrec
    def check(attempt: Int): Boolean =
      Thread.sleep(500)
      try {
        Zone:
          Pool.single(connectionString): pool =>
            pool.withLease:
              val res = sql"SELECT true".one(bool).contains(true)
              println(s"Postgres docker is running: $res")
              res
      } catch {
        case e: Throwable =>
          if attempt <= 10 then check(attempt + 1)
          else
            println(s"Could not connect to docker on $host:$port ${e.getMessage()}")
            throw e
      }

    Future(check(0))

  private def startDocker =
    useDockerImage match {
      case None => Future.unit
      case Some(image) =>
        Future {
          val pw = password.fold("")(p => s" -e POSTGRES_PASSWORD=$p")
          s"docker run -p $port:5432 -h $host -e POSTGRES_USER=$user$pw --name $pgServiceName -d $image".!!
        }.flatMap(_ => awaitReadiness)
    }

  private def rmDocker = if (useDockerImage.nonEmpty) {
    Future(s"docker rm -f $pgServiceName" ! ProcessLogger(_ => ()))
  } else Future.unit

  private def toTables(
    columns: TableMap[Column],
    indexes: TableMap[Index],
    constraints: TableMap[Constraint],
    views: Set[TableName],
  ): List[Table] = {

    def findAutoIncColumns(tableName: TableName) =
      columns
        .getOrElse(tableName, Vector.empty)
        .filter(_.default.contains(ColumnDefault.AutoInc))

    def findAutoPk(tableName: TableName): Option[Column] = findAutoIncColumns(tableName)
      .find(col =>
        constraints
          .getOrElse(tableName, Nil)
          .collect { case c: Constraint.PrimaryKey => c }
          .exists(_.columnNames.contains(col.columnName))
      )

    columns.toList.map { case (tname, tableCols) =>
      val tableConstraints = constraints.getOrElse(tname, Vector.empty)
      val generatedCols    = findAutoIncColumns(tname) ++ tableCols.filter(_.isAlwaysGenerated)
      val autoIncFk = tableConstraints.collect { case c: Constraint.ForeignKey => c }.flatMap {
        _.refs.flatMap { ref =>
          tableCols.find(c => c.columnName == ref.fromColName).filter { _ =>
            findAutoPk(ref.toTableName).exists(_.columnName == ref.toColName)
          }
        }
      }

      Table(
        name = tname,
        columns = tableCols.filterNot((generatedCols ++ autoIncFk).contains).toList,
        generatedColumns = generatedCols.toList,
        constraints = tableConstraints.toList,
        indexes = indexes.getOrElse(tname, Vector.empty).toList,
        autoIncFk = autoIncFk.toList,
        isView = views.contains(tname),
      )
    }
  }

  private def scalaEnums(enums: Enums): Vector[(Path, String)] =
    enums.map { e =>
      (
        pkgDir / s"${e.scalaName}.scala",
        s"""|package $pkgName
            |
            |import skunk.Codec
            |import skunk.data.Type
            |
            |enum ${e.scalaName}(val value: String):
            |  ${e.values.map(v => s"""case ${v.scalaName} extends ${e.scalaName}("${v.name}")""").mkString("\n  ")}
            |
            |object ${e.scalaName}:
            |  given codec: Codec[${e.scalaName}] =
            |    Codec.simple[${e.scalaName}](
            |      a => a.value,
            |      s =>${e.scalaName}.values.find(_.value == s).toRight(s"Invalid ${e.name} type: $$s"),
            |      Type("${e.name}"),
            |    )""".stripMargin,
      )
    }

  private def pkgFiles(tables: List[Table], enums: Enums): List[(Path, String)] = {
    val indexes = tables.flatMap { table =>
      table.indexes.map(i =>
        s"""val ${toScalaName(i.name)} = Index(name = "${i.name}", createSql = \"\"\"${i.createSql}\"\"\")"""
      )
    }

    val constraints = tables.flatMap { table =>
      table.constraints.map(c => s"""val ${toScalaName(c.name)} = Constraint(name = "${c.name}")""")
    }

    val arrayCodec =
      s"""|extension [A](arrCodec: skunk.Codec[skunk.data.Arr[A]])
          |  def _list(using factory: scala.collection.Factory[A, List[A]]): skunk.Codec[List[A]] =
          |    arrCodec.imap(arr => arr.flattenTo(factory))(xs => skunk.data.Arr.fromFoldable(xs))""".stripMargin

    val pkgLastPart = pkgName.split('.').last
    List(
      (
        pkgDir / "package.scala",
        List(
          s"package $pkgName",
          "",
          arrayCodec,
          if indexes.nonEmpty then indexes.mkString("\nobject indexes:\n  ", "\n  ", "\n") else "",
          if constraints.nonEmpty then constraints.mkString("\nobject constraints:\n  ", "\n  ", "\n") else "",
        ).mkString("\n"),
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
    ) ++ scalaEnums(enums)
  }

  private def toScalaType(t: Type, isNullable: Boolean, enums: Enums): Result[ScalaType] =
    t.componentTypes match {
      case Nil =>
        Map[String, List[String]](
          "Boolean"                  -> List("bool"),
          "String"                   -> List("text", "varchar", "bpchar", "name"),
          "java.util.UUID"           -> List("uuid"),
          "Short"                    -> List("int2"),
          "Int"                      -> List("int4"),
          "Long"                     -> List("int8"),
          "BigDecimal"               -> List("numeric"),
          "Float"                    -> List("float4"),
          "Double"                   -> List("float8"),
          "java.time.LocalDate"      -> List("date"),
          "java.time.LocalTime"      -> List("time"),
          "java.time.OffsetTime"     -> List("timetz"),
          "java.time.LocalDateTime"  -> List("timestamp"),
          "java.time.OffsetDateTime" -> List("timestamptz"),
          "java.time.Duration"       -> List("interval"),
        ).collectFirst {
          // check by type name without a max length parameter if set, e.g. vacrhar instead of varchar(3)
          case (scalaType, pgTypes) if pgTypes.contains(t.name.takeWhile(_ != '(')) =>
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

    def toClassPropsStr(cols: Seq[Column]) = cols
      .map(c => s"    ${c.scalaName}: ${c.scalaType}")
      .mkString("", ",\n", "")

    def toUpdateClassPropsStr(cols: Seq[Column]) = cols
      .map(c => s"    ${c.scalaName}: Option[${c.scalaType}]")
      .mkString("", ",\n", "")

    def toCodecFieldsStr(cols: Seq[Column]) = s"${cols.map(_.codecName).mkString(" *: ")}"

    def toUpdateFragment(cols: Seq[Column]) = {
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
            columns.filterNot(cstr.containsColumn).toList match {
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

      val allCols                        = autoIncFk ++ columns
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

        s"""|  def upsertQuery(updateFr: AppliedFragment, constraint: Constraint = Constraint("${cstr.name}")): $queryType =
            |    sql\"\"\"INSERT INTO #$$tableName ($allColNames) VALUES ($${$insertCodec})
            |          ON CONFLICT ON CONSTRAINT #$${constraint.name}
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
    val allCols = table.generatedColumns ++ table.autoIncFk ++ table.columns
    val cols =
      allCols.map(column =>
        s"""    val ${column.snakeCaseScalaName} = Cols(NonEmptyList.of("${column.columnName}"), ${column.codecName}, tableName)"""
      )

    val allCol =
      if table.columns.nonEmpty then
        Some {
          val s = table.columns.map(_.columnName).map(x => s""""$x"""").mkString(",")
          s"""|
              |  val all = Cols(NonEmptyList.of($s), ${table.rowClassName}.codec, tableName)
              |""".stripMargin
        }
      else None

    allCol -> cols.mkString("\n")
  }

  private def selectAllStatement(table: Table): String = {
    import table.*

    val generatedColStm = if (generatedColumns.nonEmpty) {
      val types       = generatedColumns.map(_.codecName).mkString(" *: ")
      val sTypes      = generatedColumns.map(_.scalaType).mkString(" *: ")
      val colNamesStr = (generatedColumns ++ columns).map(_.columnName).mkString(", ")

      s"""
         |  def selectAllWithGenerated[A](addClause: Fragment[A] = Fragment.empty): Query[A, $sTypes *: $rowClassName *: EmptyTuple] =
         |    sql"SELECT $colNamesStr FROM #$$tableName $$addClause".query($types *: ${rowClassName}.codec)
         |
         """.stripMargin
    } else {
      ""
    }

    val colNamesStr                   = (autoIncFk ++ columns).map(_.columnName).mkString(",")
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
    modified.sorted(using Ordering[Long].reverse).headOption

  private def outputFilesOutdated(
    sourcesModified: List[Long]
  ): (outPaths: List[File], outdated: Boolean) =
    val out =
      if Files.exists(pkgDir) then
        File(pkgDir.toString())
          .listFiles()
          .map(f => (f, f.lastModified()))
          .toList
      else Nil
    val res = for
      s <- lastModified(sourcesModified)
      o <- lastModified(out.map(_._2))
      // can't rely on timestamps when running in CI
      isNotCI = sys.env.get("CI").isEmpty
    yield isNotCI && o < s

    (outPaths = out.map(_._1), outdated = res.getOrElse(true))

  def run(forceRegeneration: Boolean = false): Future[List[File]] =
    if !scalaVersion.startsWith("3") then
      Future.failed(
        UnsupportedOperationException(s"Scala version smaller than 3 is not supported. Used version: $scalaVersion")
      )
    else
      listMigrationFiles.map { sourceFiles =>
        println(s"Found ${sourceFiles.length} source files")
        (
          sourceFiles,
          outputFilesOutdated(sourceFiles.map(_.lastModified())),
          Files.exists(pkgDir),
        )
      }.flatMap { case (sourceFiles, (outPaths, isOutdated), pkgDirExists) =>
        if ((forceRegeneration || (!pkgDirExists || isOutdated))) {
          (for {
            _ <- if sourceFiles.isEmpty then
                   Future.failed(Exception(s"Cannot find any .sql files in ${sourceDir.toPath()}"))
                 else Future.unit
            _  = if !pkgDirExists then println("Generated source not found")
            _  = if pkgDirExists && isOutdated then println("Generated source is outdated")
            _  = println("Generating Postgres models")
            _ <- rmDocker
            _ <- startDocker
            files <- generatorTask.transformWith:
                       case Success(files) => rmDocker.map(_ => files)
                       case Failure(err)   => rmDocker.flatMap(_ => Future.failed(err))
          } yield files)
        } else Future.successful(outPaths)
      }
}

object PgCodeGen {
  type TableName   = String
  type TableMap[T] = Map[TableName, Vector[T]]
  type Enums       = Vector[Enum]
  type ScalaType   = String
  type Result[T]   = Either[String, T]

  final case class Enum(name: String, values: Vector[EnumValue]) {
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
    def columnNames: Vector[String]

    def containsColumn(c: Column): Boolean = columnNames.contains(c.columnName)
  }
  object Constraint {
    final case class PrimaryKey(name: String, columnNames: Vector[String]) extends UniqueConstraint
    final case class Unique(name: String, columnNames: Vector[String])     extends UniqueConstraint
    final case class ForeignKey(name: String, refs: Vector[ColumnRef])     extends Constraint
    final case class Unknown(name: String)                                 extends Constraint
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

    val primaryUniqueConstraint: Option[UniqueConstraint] = constraints.collectFirst { case c: Constraint.PrimaryKey =>
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
