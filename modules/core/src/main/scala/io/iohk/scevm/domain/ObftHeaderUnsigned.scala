package io.iohk.scevm.domain

import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.kec256
import io.iohk.ethereum.rlp.{RLPEncodeable, RLPList, RLPSerializable}
import io.iohk.scevm.utils.SystemTime

final case class ObftHeaderUnsigned(
    parentHash: BlockHash,
    number: BlockNumber,
    slotNumber: Slot,
    beneficiary: Address,
    stateRoot: ByteString,
    transactionsRoot: ByteString,
    receiptsRoot: ByteString,
    logsBloom: ByteString,
    gasLimit: BigInt,
    gasUsed: BigInt,
    unixTimestamp: SystemTime.UnixTimestamp,
    publicSigningKey: SidechainPublicKey
) {

  //  this is not really a block hash as it is does not contain the signature
  lazy val hash: ByteString = {
    import ObftHeaderUnsigned.RLPImplicits.ObftHeaderEnc
    ByteString(kec256(this.toBytes: Array[Byte]))
  }

  def sign(prvKey: SidechainPrivateKey): ObftHeader =
    ObftHeader(
      parentHash = parentHash,
      number = number,
      slotNumber = slotNumber,
      beneficiary = beneficiary,
      stateRoot = stateRoot,
      transactionsRoot = transactionsRoot,
      receiptsRoot = receiptsRoot,
      logsBloom = logsBloom,
      gasLimit = gasLimit,
      gasUsed = gasUsed,
      unixTimestamp = unixTimestamp,
      publicSigningKey = publicSigningKey,
      signature = prvKey.sign(hash)
    )
}

object ObftHeaderUnsigned {

  def fromSigned(header: ObftHeader): ObftHeaderUnsigned = ObftHeaderUnsigned(
    parentHash = header.parentHash,
    number = header.number,
    slotNumber = header.slotNumber,
    beneficiary = header.beneficiary,
    stateRoot = header.stateRoot,
    transactionsRoot = header.transactionsRoot,
    receiptsRoot = header.receiptsRoot,
    logsBloom = header.logsBloom,
    gasLimit = header.gasLimit,
    gasUsed = header.gasUsed,
    unixTimestamp = header.unixTimestamp,
    publicSigningKey = header.publicSigningKey
  )

  object RLPImplicits {
    implicit class ObftHeaderEnc(block: ObftHeaderUnsigned) extends RLPSerializable {
      import io.iohk.ethereum.rlp.RLPImplicitConversions._
      import io.iohk.ethereum.rlp.RLPImplicits._
      override def toRLPEncodable: RLPEncodeable =
        RLPList(
          block.parentHash,
          block.number,
          block.slotNumber,
          block.beneficiary,
          block.stateRoot,
          block.transactionsRoot,
          block.receiptsRoot,
          block.logsBloom,
          block.gasLimit,
          block.gasUsed,
          block.unixTimestamp,
          block.publicSigningKey
        )
    }
  }
}
