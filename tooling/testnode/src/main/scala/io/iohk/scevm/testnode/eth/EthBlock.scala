package io.iohk.scevm.testnode.eth

import io.iohk.ethereum.rlp.{RLPEncodeable, RLPList, RLPSerializable, rawDecode}
import io.iohk.scevm.domain.SignedTransaction.SignedTransactionRLPImplicits.{
  SignedTransactionEnc,
  SignedTransactionRlpEncodableDec
}
import io.iohk.scevm.domain.TypedTransaction.TransactionsRLPAggregator
import io.iohk.scevm.domain.{
  Address,
  BlockNumber,
  ObftBlock,
  ObftBody,
  ObftHeader,
  ObftHeaderUnsigned,
  SidechainPrivateKey,
  SidechainPublicKey,
  Slot
}
import io.iohk.scevm.testnode.eth.EthBlockHeaderImplicits.{EthBlockHeaderDec, EthBlockHeaderEnc}

import scala.concurrent.duration.FiniteDuration

final case class EthBlock(header: EthBlockHeader, body: EthBlockBody)

object EthBlock {

  implicit class EthBlockEnc(val obj: EthBlock) extends RLPSerializable {

    override def toRLPEncodable: RLPEncodeable = RLPList(
      obj.header.toRLPEncodable,
      RLPList(obj.body.transactionList.map(_.toRLPEncodable): _*),
      RLPList(obj.body.uncleNodesList.map(_.toRLPEncodable): _*)
    )
  }

  implicit class EthBlockDec(val bytes: Array[Byte]) extends AnyVal {
    def toEthBlock: EthBlock = rawDecode(bytes) match {
      case RLPList(header: RLPList, stx: RLPList, uncles: RLPList) =>
        EthBlock(
          header.toEthBlockHeader,
          EthBlockBody(
            stx.items.toTypedRLPEncodables.map(_.toSignedTransaction),
            uncles.items.map(_.toEthBlockHeader)
          )
        )
      case _ => throw new RuntimeException("Cannot decode ethBlock")
    }
  }

  def size(block: EthBlock): Long = (block.toBytes: Array[Byte]).length

  def toObftBlock(
      ethBlock: EthBlock,
      obftBestHeader: ObftHeader,
      validatorPrvKey: SidechainPrivateKey,
      slotDuration: FiniteDuration
  ): ObftBlock = {
    val ethHeader = ethBlock.header
    val pubKey    = SidechainPublicKey.fromPrivateKey(validatorPrvKey)
    val obftHeader = ObftHeaderUnsigned(
      obftBestHeader.hash,
      BlockNumber(ethHeader.number),
      Slot(obftBestHeader.slotNumber.number + 1),
      Address(ethHeader.beneficiary),
      ethHeader.stateRoot,
      ethHeader.transactionsRoot,
      ethHeader.receiptsRoot,
      ethHeader.logsBloom,
      ethHeader.gasLimit,
      ethHeader.gasUsed,
      obftBestHeader.unixTimestamp.add(slotDuration),
      pubKey
    ).sign(validatorPrvKey)
    val obftBody = ObftBody(ethBlock.body.transactionList)
    ObftBlock(obftHeader, obftBody)
  }
}
