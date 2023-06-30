//> using lib "io.circe :: circe-core:0.14.1",
//> using lib "io.circe :: circe-generic:0.14.1",
//> using lib "io.circe :: circe-parser:0.14.1"
import scala.io.Source
import io.circe._, io.circe.parser._, io.circe.generic.semiauto._

final case class Node(
    hash: String,
    blockNumber: String,
    slot: String,
    signer: String
)

final case class Link(
    parent: String,
    child: String
)

final case class Dag(
    nodes: Seq[Node],
    links: Seq[Link],
    best: Seq[String],
    stable: String
)

def translateToDot(dag: Dag): String =
  def sh(s: String) = s.take(7) + ".." + s.drop(7).take(3)
  def unhex(s: String) = Integer.parseInt(s.drop(2), 16)
  val best = dag.best.toSet
  val sb = new java.lang.StringBuilder("digraph G {\n")
  dag.nodes.foreach {  n =>
    val color = if(best.contains(n.hash)) """color="green" """ else ""
    sb.append(s""""${n.hash}" [label = "no: ${unhex(n.blockNumber)};${sh(n.hash)};${sh(n.signer)}" $color]\n""")
  }
  dag.links.foreach { l => 
    val color = if(best.contains(l.parent) && best.contains(l.child)) """[color="green"]""" else ""
    sb.append(s""""${l.parent}" -> "${l.child}" $color\n""") 
  }
  sb.append("}").toString


implicit val nodeDecoder: Decoder[Node] = deriveDecoder[Node]
implicit val linkDecoder: Decoder[Link] = deriveDecoder[Link]
implicit val dagDecoder: Decoder[Dag] = deriveDecoder[Dag]


@main def transform(name: String) =
  val file = Source.fromFile(name)
  val lines = file.getLines().toList
  file.close()
  val dag = decode[Dag](lines.mkString("\n"))
  dag match {
    case Right(j) => {
      val t = translateToDot(j)
      println(t)
    }
    case Left(err) => println(s"Broken json: $err")
  }


