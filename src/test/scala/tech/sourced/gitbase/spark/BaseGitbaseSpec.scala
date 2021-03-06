package tech.sourced.gitbase.spark

import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.model.{Bind, PortBinding}
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.core.command.PullImageResultCallback
import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparkSession
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers, Suite}

trait BaseGitbaseSpec extends FlatSpec with Matchers with BeforeAndAfterAll with Logging {
  this: Suite =>

  private val resourcePath = Paths.get(getClass.getResource("/").toString)

  private val gitbaseVersion = "v0.17.0"
  private val gitbaseImage = "srcd/gitbase"

  case class Container(var id: String,
                       name: String,
                       image: String,
                       imageVersion: String,
                       bind: String,
                       ip:String,
                       port: String)

  private val container = Container(
    "",
    "server-1",
    gitbaseImage,
    gitbaseVersion,
    resourcePath.resolve("server-1").toString.substring(5) + ":/opt/repos",
    "localhost",
    "3308"
  )

  private var client: DockerClient = _
  private val runningServer: String =
    scala.util.Properties.envOrElse("TEST_GITBASE_SERVER", "") match {
      case v if v.isEmpty => ""
      case v => v.split(",").head.trim
    }

  import tech.sourced.gitbase.spark.util.GitbaseSessionBuilder

  lazy val spark = SparkSession.builder().appName("test")
    .master("local[*]")
    .config("spark.driver.host", "localhost")
    .registerGitbaseSource(server)
    .getOrCreate()

  def server: String = if (runningServer.nonEmpty) {
    runningServer
  } else {
    container.ip + ":" + container.port
  }

  override protected def beforeAll(): Unit = {
    super.beforeAll()

    // Don't instantiate the containers if there are already servers running.
    if (runningServer.nonEmpty) {
      return
    }

    client = DockerClientBuilder.getInstance().build()
    val ok = client.pullImageCmd(gitbaseImage)
      .withTag(gitbaseVersion)
      .exec(new PullImageResultCallback())
      .awaitCompletion(2, TimeUnit.MINUTES)
    if (!ok) {
      this.fail("unable to pull gitbase image")
    }

    // Remove all test running containers before initializing any.
    val runningContainers = client.listContainersCmd()
      .withShowAll(true).exec().iterator()
    while (runningContainers.hasNext) {
      val c = runningContainers.next()
      val names = c.getNames
      if (names.nonEmpty && names.head.startsWith("/test-gitbase-")) {
        logInfo(s"removed container ${c.getId}")
        client.removeContainerCmd(c.getId).withForce(true).exec()
      }
    }

    container.id = client.createContainerCmd(s"${container.image}:${container.imageVersion}")
      .withName("test-gitbase-" ++ container.name)
      .withPortBindings(PortBinding.parse(s"${container.port}:3306"))
      .withBinds(Bind.parse(container.bind))
      .exec()
      .getId()

    client.startContainerCmd(container.id).exec()
    logInfo(s"started container ${container.id} at port ${container.port}")
  }

  override protected def afterAll(): Unit = {
    super.afterAll()

    client.killContainerCmd(container.id).exec()
    client.removeContainerCmd(container.id).exec()
  }

}
