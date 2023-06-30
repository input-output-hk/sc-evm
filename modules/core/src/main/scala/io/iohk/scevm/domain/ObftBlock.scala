package io.iohk.scevm.domain

import cats.Show
import cats.implicits.showInterpolator
import io.iohk.ethereum.rlp._

/** This class represent a OBFT block as a header and a body which are returned in two different messages
  *
  * @param header ObftBlock header
  * @param body   ObftBlock body
  */
final case class ObftBlock(header: ObftHeader, body: ObftBody) {
  def fullToString: String =
    s"ObftBlock { header: ${header.fullToString}, body: ${body.fullToString} }"

  def number: BlockNumber = header.number

  def hash: BlockHash = header.hash

  def isParentOf(child: ObftBlock): Boolean = header.isParentOf(child.header)

  lazy val size: Long = encode(this).length
}

object ObftBlock {
  implicit val show: Show[ObftBlock] =
    Show.show(b => show"Block(${b.header.idTag}, #transactions=${b.body.transactionList.size})")

  import RLPImplicitConversions._
  implicit val rlpCodec: RLPCodec[ObftBlock] =
    RLPCodec.instance(
      { case ObftBlock(header, body) => RLPList(header, body) },
      { case RLPList(header, body) => ObftBlock(decode[ObftHeader](header), decode[ObftBody](body)) }
    )
}
