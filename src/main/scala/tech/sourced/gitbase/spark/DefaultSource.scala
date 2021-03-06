package tech.sourced.gitbase.spark

import org.apache.spark.SparkException
import org.apache.spark.internal.Logging
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, Expression}
import org.apache.spark.sql.Row
import org.apache.spark.sql.sources.Filter
import org.apache.spark.sql.sources.v2.reader._
import org.apache.spark.sql.sources.v2.{DataSourceOptions, DataSourceV2, ReadSupport}
import org.apache.spark.sql.types.StructType

/**
  * Contains some useful constants for the DefaultSource class to use.
  */
object DefaultSource {
  val Name = "tech.sourced.gitbase.spark"
  val TableNameKey = "table"
  val GitbaseUrlKey = "gitbase.urls"
  val GitbaseUserKey = "gitbase.user"
  val GitbasePasswordKey = "gitbase.password"
}

class DefaultSource extends DataSourceV2 with ReadSupport {

  import tech.sourced.gitbase.spark.util.ScalaOptional

  override def createReader(options: DataSourceOptions): DataSourceReader = {
    val table = options.get(DefaultSource.TableNameKey)
      .getOrElse(throw new SparkException("table parameter not provider to DataSource"))

    val user = options.get(DefaultSource.GitbaseUserKey).getOrElse("root")
    val pass = options.get(DefaultSource.GitbasePasswordKey).getOrElse("")

    val servers = options.get(DefaultSource.GitbaseUrlKey)
      .getOrElse(throw new SparkException("gitbase url parameter not provided"))
      .split(",")
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(GitbaseServer(_, user, pass))

    if (servers.isEmpty) {
      throw new SparkException("no urls to gitbase servers provided")
    }

    val schema = Gitbase.resolveTable(servers.head, table)

    DefaultReader(servers, schema, TableSource(table))
  }
}

sealed trait DataSource

case class TableSource(table: String) extends DataSource

case class JoinedSource(left: DataSource,
                        right: DataSource,
                        conditions: Option[Expression] = None) extends DataSource

case class DefaultReader(servers: Seq[GitbaseServer],
                         schema: StructType,
                         source: DataSource
                        ) extends DataSourceReader
  with SupportsPushDownRequiredColumns
  with SupportsPushDownFilters
  with Logging {

  private var requiredSchema = schema
  private var filters: Array[Filter] = Array()

  override def readSchema(): StructType = requiredSchema

  override def createDataReaderFactories(): java.util.List[DataReaderFactory[Row]] = {
    val fields = requiredSchema.fields.map(col => {
      AttributeReference(col.name, col.dataType, col.nullable, col.metadata)()
    })

    val query = QueryBuilder(fields, source, filters, schema).sql
    logDebug(s"executing query: $query")

    val list = new java.util.ArrayList[DataReaderFactory[Row]]()
    for (server <- servers) {
      list.add(DefaultDataReaderFactory(server, query))
    }

    list
  }

  override def pruneColumns(requiredSchema: StructType): Unit = {
    this.requiredSchema = requiredSchema
  }

  override def pushFilters(filters: Array[Filter]): Array[Filter] = {
    val compiled = filters.map(f => (QueryBuilder.compileFilter(f).orNull, f))
    this.filters = compiled.filter(_._1 != null).map(_._2)
    compiled.filter(_._1 == null).map(_._2)
  }

  override def pushedFilters(): Array[Filter] = filters

}

case class DefaultDataReaderFactory(server: GitbaseServer,
                                    query: String) extends DataReaderFactory[Row] {

  override def createDataReader(): DataReader[Row] = DefaultDataReader(server, query)

}

case class DefaultDataReader(server: GitbaseServer,
                             query: String) extends DataReader[Row] {

  private var iter: Iterator[Row] = _
  private var closeConn: () => Unit = _

  override def next(): Boolean = {
    if (iter == null) {
      val (iter, close) = Gitbase.query(server, query)
      this.iter = iter
      this.closeConn = close
    }

    iter.hasNext
  }

  override def get(): Row = iter.next

  override def close(): Unit = if (closeConn != null) closeConn()

}
