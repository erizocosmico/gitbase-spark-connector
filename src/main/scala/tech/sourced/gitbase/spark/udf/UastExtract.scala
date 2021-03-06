package tech.sourced.gitbase.spark.udf

import gopkg.in.bblfsh.sdk.v1.uast.generated.Position
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.functions.udf

object UastExtract extends CustomUDF {
  /** Name of the function. */
  override def name: String = "uast_extract"

  /** Function to execute when this function is called. */
  override def function: UserDefinedFunction = udf(extract _)

  def extract(marshaledNodes: Array[Byte], key: String): Option[Seq[String]] = {
    if (Option(key).getOrElse("") == "" ||
      Option(marshaledNodes).getOrElse(Array.emptyByteArray).length == 0) {
      None
    } else {
      val nodes = BblfshUtils.unmarshalNodes(marshaledNodes).getOrElse(Seq.empty)
      Some(nodes.flatMap(node => {
        key match {
          case "@type" => Seq(node.internalType)
          case "@token" => Seq(node.token)
          case "@role" => node.roles.map(_.toString)
          case "@startpos" => Seq(node.startPosition.getOrElse(Position()).toProtoString)
          case "@endpos" => Seq(node.startPosition.getOrElse(Position()).toProtoString)
          case _ => Seq.empty
        }
      }).filter(_.nonEmpty))
    }
  }
}
