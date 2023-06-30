package io.iohk.scevm.domain

import cats.Show
import io.iohk.bytes.ByteString
import io.iohk.ethereum.rlp._
import io.iohk.scevm.domain.TypedTransaction.TransactionsRLPAggregator
import io.iohk.scevm.mpt.MptStorage

final case class ObftBody(
    transactionList: Seq[SignedTransaction]
) {
  def fullToString: String =
    s"ObftBody { transactionList: $transactionList }"

  def toShortString: String =
    s"ObftBody { transactionsList: ${transactionList.map(_.hashAsHexString)} }"

  lazy val transactionsRoot: ByteString =
    MptStorage.rootHash(transactionList, SignedTransaction.byteArraySerializable)
}

object ObftBody {
  val empty: ObftBody = ObftBody(Seq.empty)

  implicit val show: Show[ObftBody] = Show.show(_.toShortString)

  import SignedTransaction.SignedTransactionRLPImplicits._
  implicit val rlpCodec: RLPCodec[ObftBody] =
    RLPCodec.instance(
      { case ObftBody(transactionList) => RLPList(RLPList(transactionList.map(_.toRLPEncodable): _*)) },
      { case RLPList(transactions: RLPList) =>
        ObftBody(transactions.items.toTypedRLPEncodables.map(_.toSignedTransaction))
      }
    )
}
