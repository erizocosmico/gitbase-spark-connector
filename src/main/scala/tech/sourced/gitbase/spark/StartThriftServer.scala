package tech.sourced.gitbase.spark

import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.hive.thriftserver.HiveThriftServer2

object StartThriftServer extends App with Logging {

  logInfo("Registering gitbase-spark-connector...")

  val spark = SparkSession.builder().appName("Thrift server with gitbase")
    .registerGitbaseSource()
    .config("spark.sql.hive.thriftServer.singleSession","true")
    .getOrCreate()

  logInfo("gitbase-spark-connector registered!")

  HiveThriftServer2.startWithContext(spark.sqlContext)
}
