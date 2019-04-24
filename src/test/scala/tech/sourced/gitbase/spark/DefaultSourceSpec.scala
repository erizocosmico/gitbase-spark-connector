package tech.sourced.gitbase.spark

import java.util.Properties
import java.nio.file.Paths
import org.apache.commons.io.FileUtils

class DefaultSourceSpec extends BaseGitbaseSpec {

  behavior of "DefaultSourceSpec"

  it should "count all repositories" in {
    spark.table("repositories").count() should equal(3)
  }

  it should "have blob columns as binary" in {
    spark.sql("describe table files")
      .collect()
      .find(r => r(0).toString == "blob_content")
      .map(r => r(1).toString)
      .getOrElse("") should be("binary")
  }

  it should "perform joins and filters" in {
    val df = spark.sql(
      """
        |SELECT * FROM ref_commits r
        |INNER JOIN commits c
        | ON r.repository_id = c.repository_id
        | AND r.commit_hash = c.commit_hash
        |WHERE r.history_index = 0
      """.stripMargin)

    df.count() should be(56)
    for (row <- df.collect()) {
      row.length should be(15)
    }
  }

  it should "get all the repositories where a specific user contributes on HEAD reference" in {
    val df = spark.sql("SELECT refs.repository_id, commits.commit_author_name" +
      " FROM refs" +
      " NATURAL JOIN commits" +
      " WHERE refs.ref_name REGEXP '^refs/heads/HEAD/'" +
      " AND commits.commit_author_name = 'wangbo'")

    val rows = df.collect()
    rows.length should be(2)
    for (row <- rows) {
      row(0).asInstanceOf[String] should be("fff7062de8474d10a67d417ccea87ba6f58ca81d.siva")
      row.length should be(2)
    }
  }

  it should "get all the HEAD references from all repositories" in {
    val df = spark.sql("SELECT refs.repository_id, refs.ref_name" +
      " FROM refs" +
      " WHERE refs.ref_name REGEXP '^refs/heads/HEAD/'")

    df.count() should be(5)
    for (row <- df.collect()) {
      row.length should be(2)
    }
  }

  it should "get the files in the first commit on HEAD history for all repositories" in {
    val df = spark.sql("SELECT" +
      " file_path," +
      " repository_id" +
      " FROM commit_files f" +
      " NATURAL JOIN ref_commits r" +
      " WHERE r.ref_name REGEXP '^refs/heads/HEAD/'" +
      " AND r.history_index = 0")

    df.count() should be(459)
    for (row <- df.collect()) {
      row.length should be(2)
    }
  }

  it should "get commits that appear in more than one reference" in {
    val df = spark.sql("SELECT * FROM (" +
      " SELECT COUNT(commit_hash) AS num, commit_hash" +
      " FROM ref_commits r" +
      " NATURAL JOIN commits c" +
      " GROUP BY commit_hash" +
      ") t WHERE num > 1")

    df.count() should be(1046)
    for (row <- df.collect()) {
      row.length should be(2)
    }
  }

  it should "get commits that appear in more than one reference without natural join" in {
    val df = spark.sql("SELECT * FROM (" +
      " SELECT COUNT(c.commit_hash) AS num, c.commit_hash" +
      " FROM ref_commits r" +
      " INNER JOIN commits c" +
      " ON c.commit_hash = r.commit_hash" +
      " AND c.repository_id = r.repository_id" +
      " GROUP BY c.commit_hash" +
      ") t WHERE num > 1")

    df.count() should be(1046)
    for (row <- df.collect()) {
      row.length should be(2)
    }
  }

  it should "get the number of blobs per head commit" in {
    val df = spark.sql("SELECT COUNT(c.commit_hash), c.commit_hash" +
      " FROM ref_commits as r" +
      " INNER JOIN commits c" +
      "   ON r.commit_hash = c.commit_hash" +
      "   AND r.repository_id = c.repository_id" +
      " INNER JOIN commit_blobs cb" +
      "   ON cb.commit_hash = c.commit_hash" +
      "   AND cb.repository_id = c.repository_id" +
      " WHERE r.ref_name REGEXP '^refs/heads/HEAD/'" +
      " GROUP BY c.commit_hash")

    df.count() should be(985)
    for (row <- df.collect()) {
      row.length should be(2)
    }
  }

  it should "get commits per commiter per month, in 2015" in {
    val df = spark.sql(
      """
        |SELECT COUNT(*) as num_commits, month, repo_id, committer_email
        |FROM (
        |    SELECT
        |        MONTH(committer_when) as month,
        |        r.repository_id as repo_id,
        |        committer_email
        |    FROM ref_commits r
        |    INNER JOIN commits c
        |            ON r.commit_hash = c.commit_hash
        |            AND r.repository_id = c.repository_id
        |    WHERE r.ref_name REGEXP '^refs/heads/HEAD/'
        |    AND YEAR(c.committer_when) = 2015
        |) as t
        |GROUP BY committer_email, month, repo_id
      """.stripMargin
    )

    df.count() should be(30)
    for (row <- df.collect()) {
      row.length should be(4)
    }
  }

  it should "get files from first 6 commits from HEAD references that contain" +
    " some key and are not in vendor directory" in {
    val df = spark.sql(
      """
        |select
        |    file_path,
        |    repository_id,
        |    blob_content
        |FROM
        |    files
        |NATURAL JOIN
        |    commit_files
        |NATURAL JOIN
        |    ref_commits
        |WHERE
        |    ref_name REGEXP '^refs/heads/HEAD/'
        |    AND history_index BETWEEN 0 AND 5
        |    AND is_binary(blob_content) = false
        |    AND file_path NOT REGEXP '^vendor.*'
        |    AND (
        |        blob_content REGEXP '(?i)facebook.*[\'\\"][0-9a-f]{32}[\'\\"]'
        |        OR blob_content REGEXP '(?i)twitter.*[\'\\"][0-9a-zA-Z]{35,44}[\'\\"]'
        |        OR blob_content REGEXP '(?i)github.*[\'\\"][0-9a-zA-Z]{35,40}[\'\\"]'
        |        OR blob_content REGEXP 'AKIA[0-9A-Z]{16}'
        |        OR blob_content REGEXP '(?i)reddit.*[\'\\"][0-9a-zA-Z]{14}[\'\\"]'
        |        OR blob_content REGEXP '(?i)heroku.*[0-9A-F]{8}-[0-9A-F]
        |{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}'
        |        OR blob_content REGEXP '.*-----BEGIN PRIVATE KEY-----.*'
        |        OR blob_content REGEXP '.*-----BEGIN RSA PRIVATE KEY-----.*'
        |        OR blob_content REGEXP '.*-----BEGIN DSA PRIVATE KEY-----.*'
        |        OR blob_content REGEXP '.*-----BEGIN OPENSSH PRIVATE KEY-----.*'
        |    )
      """.stripMargin)

    df.count() should be(0) // TODO(erizocosmico): this might not be correct
    for (row <- df.collect()) {
      row.length should be(4)
    }
  }

  it should "extract information from uast udfs" in {
    val df = spark.sql(
      """
          SELECT
            file_path,
            uast_extract_parse(
              uast_extract(
                uast(
                  blob_content,
                  language(
                    file_path,
                    blob_content
                  ),
                  "//FuncLit"
                ),
                "internalRole"
              )
            )
       FROM files
       WHERE language(file_path, blob_content) = 'Python'
       LIMIT 100""")
    df.count() should be(2)

    for (row <- df.collect()) {
      row.length should be(2)
    }

  }

  it should "get commit parents using parse_commit_parents" in {
    val result = spark.sql("SELECT repository_id, parse_commit_parents(commit_parents) AS parents" +
      " FROM ref_commits" +
      " NATURAL JOIN commits" +
      " WHERE ref_name LIKE 'refs/heads/HEAD/%' AND history_index = 0" +
      " ORDER BY repository_id, parents")
      .collect()
      .map(row => (row(0), row(1).asInstanceOf[Seq[String]]))

    result should equal(Array(
      ("05893125684f2d3943cd84a7ab2b75e53668fba1.siva",
        Seq("05e39f6b6f89eb7f9e53e42bffae844b5d869b90")),
      ("fff7062de8474d10a67d417ccea87ba6f58ca81d.siva", Seq()),
      ("fff7062de8474d10a67d417ccea87ba6f58ca81d.siva", Seq()),
      ("fff840f8784ef162dc83a1465fc5763d890b68ba.siva",
        Seq("9956dc89b79e37e99ec09a7c3dc18291622cfc26")),
      ("fff840f8784ef162dc83a1465fc5763d890b68ba.siva",
        Seq("e9276d0ef0c802bc268eea56d05c0abca4d37ee0"))
    ))
  }

  it should "show 20 results without errors" in {
    val df = spark.sql(
      """
        |SELECT
        |  *
        |FROM (
        |  SELECT
        |    COUNT(c.commit_hash) as num, c.commit_hash
        |  FROM
        |    ref_commits r
        |  INNER JOIN
        |    commits c
        |  ON
        |    r.repository_id = c.repository_id AND r.commit_hash = c.commit_hash
        |  GROUP BY
        |    c.commit_hash
        |) t
        |WHERE
        |  num > 1
      """.stripMargin)
    df.show(20, truncate = false)
  }

  /***********************************/
  /******* REFERENCE QUERIES *********/
  /***********************************/

  it should "count distinct repositories" in {
    val result = spark.sql(
      """SELECT COUNT(DISTINCT repository_id) AS repository_count
      FROM repositories""").collect()
    result.length should be(1)
    result(0)(0) should be(3)
  }

  it should "select the last commit messages in HEAD for every repo" in {
    val result = spark.sql(
      """SELECT repository_id, commit_message
                             FROM ref_commits
                             NATURAL JOIN commits
                             WHERE
                                 ref_name LIKE 'refs/heads/HEAD/%' AND
                                 history_index = 0
                             ORDER BY repository_id""").collect()

    result(0)(0) should be("05893125684f2d3943cd84a7ab2b75e53668fba1.siva")
    result(0)(1) should be("Updating relationships.\n")

    result(1)(0) should be("fff7062de8474d10a67d417ccea87ba6f58ca81d.siva")
    result(1)(1) should be("Initial commit\n")

    result(2)(0) should be("fff7062de8474d10a67d417ccea87ba6f58ca81d.siva")
    result(2)(1) should be("Initial commit\n")

    result(3)(0) should be("fff840f8784ef162dc83a1465fc5763d890b68ba.siva")
    result(3)(1).toString.split("\n").head should
      be("SVN_SILENT made messages (.desktop file) - always resolve ours")
  }

  it should "get all commit messages in HEAD history for each repository" in {
    spark.sql(
      """SELECT commit_message
                FROM ref_commits
                NATURAL JOIN commits
                WHERE
                    ref_name LIKE 'refs/heads/HEAD/%' AND
                    history_index >= 0""").count() should be(1059)
  }

  it should "get the top 10 repositories by commit count on HEAD" in {
    val result = spark.sql(
      """SELECT
                    r.repository_id,
                    count(*) AS commit_count
                FROM ref_commits r
                NATURAL JOIN commits c
                 WHERE
                    r.ref_name LIKE 'refs/heads/HEAD/%'
                GROUP BY r.repository_id, r.ref_name
                ORDER BY commit_count DESC
                LIMIT 10""").collect().map(r => (r(0), r(1)))

    result should equal(Array(
      ("05893125684f2d3943cd84a7ab2b75e53668fba1.siva", 606),
      ("fff840f8784ef162dc83a1465fc5763d890b68ba.siva", 379),
      ("fff840f8784ef162dc83a1465fc5763d890b68ba.siva", 72),
      ("fff7062de8474d10a67d417ccea87ba6f58ca81d.siva", 1),
      ("fff7062de8474d10a67d417ccea87ba6f58ca81d.siva", 1)
    ))
  }

  it should "count repository HEADs" in {
    spark.sql(
      """SELECT
                    repository_id,
                    COUNT(repository_id) AS head_count
                FROM refs
                WHERE ref_name LIKE 'refs/heads/HEAD/%'
                GROUP BY repository_id""").count() should be(3)
  }

  it should "get the top 50 tokens in files" in {
    val result = spark.sql(
      """SELECT
                    token, COUNT(repository_id) AS repository_count
                FROM (
                    SELECT
                        repository_id,
                        EXPLODE(SPLIT(blob_content, '[^_A-Za-z0-9]+')) AS token
                    FROM ref_commits
                    NATURAL JOIN commit_blobs
                    NATURAL JOIN blobs
                    WHERE
                        ref_name LIKE 'refs/heads/HEAD/%' AND
                        NOT IS_BINARY(blob_content) AND
                        LENGTH(blob_content) < 524288
                ) AS q
                GROUP BY token
                ORDER BY repository_count DESC
                LIMIT 50""")
      .collect()
      .map(row => (row(0).toString, row(1).asInstanceOf[Long]))

    result.take(5) should equal(Array(
      ("end", 1165439L),
      ("should", 945547L),
      ("the", 650752L),
      ("do", 635618L),
      ("a", 515144L)
    ))
  }

  ignore should "get the top 50 identifiers in Ruby files by repository count" in {
    // FIXME: https://github.com/src-d/gitbase-spark-connector/issues/65
    spark.sql(
      """SELECT
                    identifier,
                    COUNT(repository_id) AS repository_count
                FROM (
                    SELECT
                        repository_id AS repository_id,
                        EXPLODE(
                          uast_extract_parse(
                            uast_extract(
                              uast(
                                blob_content,
                                language(file_path, blob_content),
                                '//*[@roleIdentifier and not(@roleIncomplete)]'
                              ),
                              'token'
                            )
                          )
                        ) AS identifier
                    FROM ref_commits
                    NATURAL JOIN commit_files
                    NATURAL JOIN files
                    WHERE
                      ref_name LIKE 'refs/heads/HEAD/%' AND
                      NOT IS_BINARY(blob_content) AND
                      LENGTH(blob_content) < 25000 AND
                      file_path REGEXP '\.(rb)$'
                ) AS q
                GROUP BY identifier
                ORDER BY repository_count DESC
                LIMIT 50""").show()
  }

  it should "get top 10 repositories by contributor count" in {
    val result = spark.sql(
      """SELECT
                    repository_id,
                    contributor_count
                FROM (
                    SELECT
                        repository_id,
                        COUNT(DISTINCT commit_author_email) AS contributor_count
                    FROM commits
                    GROUP BY repository_id
                ) AS q
                ORDER BY contributor_count DESC
                LIMIT 10""")
      .collect()
      .map(row => (row(0).toString, row(1).asInstanceOf[Long]))

    result should equal(Array(
      ("fff840f8784ef162dc83a1465fc5763d890b68ba.siva", 12L),
      ("05893125684f2d3943cd84a7ab2b75e53668fba1.siva", 8L),
      ("fff7062de8474d10a67d417ccea87ba6f58ca81d.siva", 5L)
    ))
  }

  it should "get projects created per year" in {
    val result = spark.sql(
      """SELECT
                    year,
                    COUNT(DISTINCT repository_id) AS project_count
                FROM (
                    SELECT
                        repository_id,
                        MIN(YEAR(commit_author_when)) AS year
                    FROM ref_commits
                    NATURAL JOIN commits
                    WHERE
                        ref_name LIKE 'refs/heads/HEAD/%'
                    GROUP BY repository_id
                ) AS q
                GROUP BY year
                ORDER BY year DESC""").collect().map(r => (r(0), r(1)))
    result should equal(Array(
      (2015, 1),
      (2014, 1),
      (2007, 1)
    ))
  }

  it should "approximately detect forks" in {
    val result = spark.sql(
      """SELECT
                     commit_hash,
                     n_repos,
                     s.repository_id AS main_repository_id,
                     repository_ids
                 FROM (
                     SELECT
                         commit_hash,
                         COUNT(*) n_repos,
                         MAX(STRUCT(history_index, repository_id)) AS s,
                         COLLECT_SET(repository_id) AS repository_ids
                     FROM ref_commits
                     NATURAL JOIN commits
                     WHERE
                         history_index != 1
                         AND ref_name LIKE 'refs/heads/HEAD/%'
                         AND SIZE(parse_commit_parents(commit_parents)) == 0
                     GROUP BY commit_hash
                 ) AS q
                 WHERE n_repos > 1
                 ORDER BY n_repos DESC""").collect().map(r => (r(0), r(1)))

    result should equal(Array(
      ("fff7062de8474d10a67d417ccea87ba6f58ca81d", 2),
      ("fff840f8784ef162dc83a1465fc5763d890b68ba", 2)
    ))
  }

  it should "count HEADs excluding forks" in {
    val result = spark.sql(
      """SELECT
                    COUNT(*)
                FROM (
                  SELECT DISTINCT
                       s.repository_id AS repository_id
                  FROM (
                      SELECT
                          commit_hash,
                          MAX(STRUCT(history_index, repository_id)) AS s
                      FROM ref_commits
                      NATURAL JOIN commits
                      WHERE
                          history_index != 1
                          AND ref_name LIKE 'refs/heads/HEAD/%'
                          AND SIZE(PARSE_COMMIT_PARENTS(commit_parents)) == 0
                      GROUP BY commit_hash
                  ) AS q
                ) AS q2""").collect()(0)(0)
    result should be(3L)
  }

  it should "do repository count by language presence" in {
    val result = spark.sql(
      """SELECT
                    language,
                    COUNT(repository_id) AS repository_count
                FROM (
                    SELECT DISTINCT
                        repository_id,
                        COALESCE(language(file_path, blob_content), 'Unknown') AS language
                    FROM ref_commits
                    NATURAL JOIN commit_files
                    NATURAL JOIN files
                    WHERE
                        ref_name LIKE 'refs/heads/HEAD/%'
                    ) AS q2
                GROUP BY language
                ORDER BY repository_count DESC, language ASC""").collect().map(r => (r(0), r(1)))

    result should equal(Array(
      ("Text", 3),
      ("", 2),
      ("Markdown", 2),
      ("C", 1),
      ("C++", 1),
      ("CMake", 1),
      ("Ignore List", 1),
      ("JSON", 1),
      ("JavaScript", 1),
      ("QML", 1),
      ("Ruby", 1),
      ("Shell", 1),
      ("XML", 1),
      ("desktop", 1)
    ))
  }

  it should "pull data using JDBC correctly" in {
    val props = new Properties()
    props.put("user", "root")
    props.put("password", "")
    props.put("driver", "org.mariadb.jdbc.Driver")

    val rcdf = spark.read.jdbc(s"jdbc:mariadb://$server/gitbase", "ref_commits", props)

    val result = rcdf.collect()
    result.length should be(spark.table("ref_commits").count())

    result.foreach(row => {
      row(0).toString should not(be("repository_id"))
      row(1).toString should not(be("commit_hash"))
      row(2).toString should not(be("ref_name"))
      row(3).isInstanceOf[Long] should be(true)
    })
  }

  it should "join with other data sources correctly" in {
    val path = Paths.get(System.getProperty("java.io.tmpdir")).resolve("test-json-data")
    try {
      spark.sql("SELECT * FROM ref_commits WHERE ref_name REGEXP '/head'")
        .write.json(path.toString)

      val jsonDf = spark.read.json(path.toString)
      val commitsDf = spark.table("commits")
      val df = commitsDf.join(jsonDf, Seq("repository_id", "commit_hash"))
        .selectExpr("commit_message", "commit_author_email")
        .orderBy("commit_author_email")

      val result = df.limit(2).collect().map(_ (1).toString)
      result.length should be(2)
      result(0) should be("(no author)@ff5234a3-732a-0410-8ecb-13b9ad946718")
      result(1) should be("(no author)@ff5234a3-732a-0410-8ecb-13b9ad946718")
    } finally {
      FileUtils.deleteQuietly(path.toFile)
    }
  }

}
