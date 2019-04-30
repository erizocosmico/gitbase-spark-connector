import sbt._

object Dependencies {
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.5"
  lazy val sparkSql = "org.apache.spark" %% "spark-sql" % "2.3.1"
  lazy val sparkHiveThriftserver = "org.apache.spark" %% "spark-hive-thriftserver" % "2.3.1"
  lazy val mysql = "mysql" % "mysql-connector-java" % "8.0.16"
  lazy val enry = "tech.sourced" % "enry-java" % "1.7.1"
  lazy val bblfsh = "org.bblfsh" % "bblfsh-client" % "1.10.1"
  lazy val dockerJava = "com.github.docker-java" % "docker-java" % "3.0.14"
}
