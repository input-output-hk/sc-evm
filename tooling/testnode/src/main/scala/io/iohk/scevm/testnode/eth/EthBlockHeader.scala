package io.iohk.scevm.testnode.eth

import io.iohk.bytes.ByteString
import io.iohk.ethereum.rlp.{RLPEncodeable, RLPList, RLPSerializable, rawDecode}
import io.iohk.scevm.testnode.eth.HeaderExtraFields.HefEmpty

final case class EthBlockHeader(
    parentHash: ByteString,
    ommersHash: ByteString,
    beneficiary: ByteString,
    stateRoot: ByteString,
    transactionsRoot: ByteString,
    receiptsRoot: ByteString,
    logsBloom: ByteString,
    difficulty: BigInt,
    number: BigInt,
    gasLimit: BigInt,
    gasUsed: BigInt,
    unixTimestamp: Long,
    extraData: ByteString,
    mixHash: ByteString,
    nonce: ByteString,
    extraFields: HeaderExtraFields = HefEmpty
)

sealed trait HeaderExtraFields
object HeaderExtraFields {
  case object HefEmpty extends HeaderExtraFields
}

object EthBlockHeaderImplicits {

  import io.iohk.ethereum.rlp.RLPImplicitConversions._
  import io.iohk.ethereum.rlp.RLPImplicits._

  implicit class EthBlockHeaderEnc(blockHeader: EthBlockHeader) extends RLPSerializable {
    // scalastyle:off method.length
    override def toRLPEncodable: RLPEncodeable = {
      import blockHeader._
      extraFields match {
        case HefEmpty =>
          RLPList(
            parentHash,
            ommersHash,
            beneficiary,
            stateRoot,
            transactionsRoot,
            receiptsRoot,
            logsBloom,
            difficulty,
            number,
            gasLimit,
            gasUsed,
            unixTimestamp,
            extraData,
            mixHash,
            nonce
          )
      }
    }
  }

  implicit class EthBlockHeaderByteArrayDec(val bytes: Array[Byte]) extends AnyVal {
    def toBlockHeader: EthBlockHeader = EthBlockHeaderDec(rawDecode(bytes)).toEthBlockHeader
  }

  implicit class EthBlockHeaderDec(val rlpEncodeable: RLPEncodeable) extends AnyVal {
    // scalastyle:off method.length
    def toEthBlockHeader: EthBlockHeader =
      rlpEncodeable match {

        case RLPList(
              parentHash,
              ommersHash,
              beneficiary,
              stateRoot,
              transactionsRoot,
              receiptsRoot,
              logsBloom,
              difficulty,
              number,
              gasLimit,
              gasUsed,
              unixTimestamp,
              extraData,
              mixHash,
              nonce
            ) =>
          EthBlockHeader(
            parentHash,
            ommersHash,
            beneficiary,
            stateRoot,
            transactionsRoot,
            receiptsRoot,
            logsBloom,
            difficulty,
            number,
            gasLimit,
            gasUsed,
            unixTimestamp,
            extraData,
            mixHash,
            nonce
          )

        case RLPList(
              parentHash,
              ommersHash,
              beneficiary,
              stateRoot,
              transactionsRoot,
              receiptsRoot,
              logsBloom,
              difficulty,
              number,
              gasLimit,
              gasUsed,
              unixTimestamp,
              extraData,
              mixHash,
              nonce
            ) =>
          EthBlockHeader(
            parentHash,
            ommersHash,
            beneficiary,
            stateRoot,
            transactionsRoot,
            receiptsRoot,
            logsBloom,
            difficulty,
            number,
            gasLimit,
            gasUsed,
            unixTimestamp,
            extraData,
            mixHash,
            nonce
          )

        case _ =>
          throw new Exception("BlockHeader cannot be decoded")
      }
  }
}
