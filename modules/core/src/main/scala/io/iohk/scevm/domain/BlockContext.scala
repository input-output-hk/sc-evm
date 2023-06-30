package io.iohk.scevm.domain

import io.iohk.bytes.ByteString
import io.iohk.scevm.utils.SystemTime.UnixTimestamp

final case class BlockContext(
    parentHash: BlockHash,
    number: BlockNumber,
    slotNumber: Slot,
    gasLimit: BigInt,
    coinbase: Address,
    unixTimestamp: UnixTimestamp
) {
  def toUnsignedHeader(
      bloomFilter: ByteString,
      gasUsed: BigInt,
      receiptsRoot: ByteString,
      stateRoot: ByteString,
      transactionsRoot: ByteString,
      validatorPubKey: SidechainPublicKey
  ): ObftHeaderUnsigned =
    ObftHeaderUnsigned(
      parentHash = parentHash,
      number = number,
      slotNumber = slotNumber,
      beneficiary = coinbase,
      stateRoot = stateRoot,
      transactionsRoot = transactionsRoot,
      receiptsRoot = receiptsRoot,
      logsBloom = bloomFilter,
      gasLimit = gasLimit,
      gasUsed = gasUsed,
      unixTimestamp = unixTimestamp,
      publicSigningKey = validatorPubKey
    )
}

object BlockContext {
  def from(header: ObftHeader): BlockContext = BlockContext(
    header.parentHash,
    header.number,
    header.slotNumber,
    header.gasLimit,
    header.beneficiary,
    header.unixTimestamp
  )
}
