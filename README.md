# gitbase-spark-connector [![Build Status](https://travis-ci.org/src-d/gitbase-spark-connector.svg?branch=master)](https://travis-ci.org/src-d/gitbase-spark-connector) [![codecov](https://codecov.io/gh/src-d/gitbase-spark-connector/branch/master/graph/badge.svg)](https://codecov.io/gh/src-d/gitbase-spark-connector)

**gitbase-spark-connector** is a [Scala](https://www.scala-lang.org/) library that lets you expose [gitbase](https://www.github.com/src-d/gitbase) tables as [Spark SQL](https://spark.apache.org/sql/) Dataframes to run scalable analysis and processing pipelines on source code.

## Pre-requisites

* [Scala](https://www.scala-lang.org/) 2.11.12
* [Apache Spark 2.3.2 Installation](http://spark.apache.org/docs/2.3.2)
* [gitbase](https://github.com/src-d/gitbase) >= v0.20.0-beta3
* [bblfsh](https://github.com/bblfsh/bblfshd) >= 2.10.x

## Import as a dependency

Maven:

```xml
<dependency>
  <groupId>tech.sourced</groupId>
  <artifactId>gitbase-spark-connector</artifactId>
  <version>[version]</version>
  <type>slim</type>
</dependency>
```

SBT:

```scala
libraryDependencies += "tech.sourced" % "gitbase-spark-connector" % "[version]" classifier "slim"
```

Note the `slim` type or classifier. 
This is intended to make possible use this data source as a library.
If you don't add it, the retrieved jar will have all the needed dependencies included (fat-jar or uber-jar). 
That might cause dependency conflicts in your application.

You can check the available versions [here](https://search.maven.org/search?q=a:gitbase-spark-connector).

## Usage

First of all, you'll need a [gitbase](https://www.github.com/src-d/gitbase) instance running. It will expose your repositories through a SQL interface.
Gitbase depends on [bblfsh](https://github.com/bblfsh/bblfshd), to extract *UAST* (universal abstract syntax tree) from source code. For instance if you plan to filter queries by language or generally run some operations on [UASTs](https://docs.sourced.tech/babelfish/uast/uast-v2) then babelfish server is required.

The most convenient way is to run all services with docker-compose. This Compose file (docker-compose.yml) defines three services (bblfshd, gitbase and gitbase-spark-connector).

You can run any combination of them, e.g. (only bblfshd and gitbase):
- Note: You must change `/path/to/repos` in `docker-compose.yml` (for gitbase volumes) to the actual path where your git repositories are located.

```bash
$ docker-compose up bblfshd gitbase
```
All containers run in the same network. Babelfish server will be exposed on port `:9432`, Gitbase server is linked to Babelfish and exposed on port `:3306`, and Spark connector is linked to both (bblfsh and gitbase) and serves *Jupyter Notebook* on port `:8080`.

The command:
```bash
$ docker-compose up
```
runs all services, but first it builds a Docker image (based on Dockerfile) for `gitbase-spark-connector`.
If all services started without any errors, you can go to `http://localhost:8080` and play with *Jupyter Notebook* to query _gitbase_ via _spark connector_.

Finally you can try it out from your code. Add the gitbase `DataSource` and configuration by registering in the spark session.

```scala
import tech.sourced.gitbase.spark.GitbaseSessionBuilder

// Add these lines if your are using spark-shell

// import org.apache.spark.sql.SparkSession
// SparkSession.setActiveSession(null)
// SparkSession.setDefaultSession(null)

val spark = SparkSession.builder().appName("test")
    .master("local[*]")
    .config("spark.driver.host", "localhost")
    .registerGitbaseSource()
    .getOrCreate()

val refs = spark.table("ref_commits")
val commits = spark.table("commits")

val df = refs
  .join(commits, Seq("repository_id", "commit_hash"))
  .filter(refs("history_index") === 0)

df.select("ref_name", "commit_hash", "committer_when").show(false)
```

Output:
```
+-------------------------------------------------------------------------------+----------------------------------------+-------------------+
|ref_name                                                                       |commit_hash                             |committer_when     |
+-------------------------------------------------------------------------------+----------------------------------------+-------------------+
|refs/heads/HEAD/015dcc49-9049-b00c-ba72-b6f5fa98cbe7                           |fff7062de8474d10a67d417ccea87ba6f58ca81d|2015-07-28 08:39:11|
|refs/heads/HEAD/015dcc49-90e6-34f2-ac03-df879ee269f3                           |fff7062de8474d10a67d417ccea87ba6f58ca81d|2015-07-28 08:39:11|
|refs/heads/develop/015dcc49-9049-b00c-ba72-b6f5fa98cbe7                        |880653c14945dbbc915f1145561ed3df3ebaf168|2015-08-19 01:02:38|
|refs/heads/HEAD/015da2f4-6d89-7ec8-5ac9-a38329ea875b                           |dbfab055c70379219cbcf422f05316fdf4e1aed3|2008-02-01 16:42:40|
+-------------------------------------------------------------------------------+----------------------------------------+-------------------+
```

## Regular expressions

When a query is executed using `gitbase-spark-connector`, that query either fully or partially will be sent down for `gitbase` to execute.
`gitbase` uses oniguruma as its regular expression engine, which is different to the one being used by Spark. So, regular expressions need to be written in a way that can be executed in both places, because depending on the query it can be executed in just one of the places or both.

- [Oniguruma syntax](https://github.com/geoffgarside/oniguruma/blob/master/Syntax.txt)
- [Java regular expression syntax](https://docs.oracle.com/javase/9/docs/api/java/util/regex/Pattern.html)

## License

Apache License 2.0, see [LICENSE](/LICENSE)
