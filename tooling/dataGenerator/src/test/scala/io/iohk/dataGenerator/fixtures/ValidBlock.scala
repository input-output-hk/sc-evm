package io.iohk.dataGenerator.fixtures

import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignature}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{
  Address,
  BlockContext,
  BlockHash,
  BlockNumber,
  ObftBlock,
  ObftBody,
  ObftHeader,
  ObftHeaderUnsigned,
  SignedTransaction,
  Slot
}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp

object ValidBlock {

  val dummySig: ECDSASignature = {
    val signatureRandom =
      Hex.decodeUnsafe("f3af65a23fbf207b933d3c962381aa50e0ac19649c59c1af1655e592a8d95401")
    val signature = Hex.decodeUnsafe("53629a403579f5ce57bcbefba2616b1c6156d308ddcd37372c94943fdabeda97")
    val pointSign = 28

    ECDSASignature(BigInt(1, signatureRandom.toArray[Byte]), BigInt(1, signature.toArray[Byte]), pointSign.toByte)
  }

  val header: ObftHeader = ObftHeader(
    parentHash = BlockHash(Hex.decodeUnsafe("8345d132564b3660aa5f27c9415310634b50dbc92579c65a0825d9a255227a71")),
    beneficiary = Address("df7d7e053933b5cc24372f878c90e62dadad5d42"),
    stateRoot = Hex.decodeUnsafe("087f96537eba43885ab563227262580b27fc5e6516db79a6fc4d3bcd241dda67"),
    transactionsRoot = Hex.decodeUnsafe("8ae451039a8bf403b899dcd23252d94761ddd23b88c769d9b7996546edc47fac"),
    receiptsRoot = Hex.decodeUnsafe("8b472d8d4d39bae6a5570c2a42276ed2d6a56ac51a1a356d5b17c5564d01fd5d"),
    logsBloom = Hex.decodeUnsafe("0" * 512),
    number = BlockNumber(3125369),
    slotNumber = Slot(4567),
    gasLimit = 4699996,
    gasUsed = 84000,
    unixTimestamp = 1486131165L.millisToTs,
    publicSigningKey = ECDSA.PublicKey.Zero,
    signature = dummySig
  )

  val headerUnsigned: ObftHeaderUnsigned = ObftHeaderUnsigned.fromSigned(header)
  val blockContext: BlockContext         = BlockContext.from(header)

  val body: ObftBody = ObftBody(transactionList = Seq.empty[SignedTransaction])

  val transactionHashes: Seq[ByteString] = Seq(
    Hex.decodeUnsafe("af854c57c64191827d1c80fc50f716f824508973e12e4d4c60d270520ce72edb"),
    Hex.decodeUnsafe("f3e33ba2cb400221476fa4025afd95a13907734c38a4a8dff4b7d860ee5adc8f"),
    Hex.decodeUnsafe("202359a4c0b0f11ca07d44fdeb3502ffe91c86ad4a9af47c27f11b23653339f2"),
    Hex.decodeUnsafe("067bd4b1a9d37ff932473212856262d59f999935a4a357faf71b1d7e276b762b")
  )

  val size: Long = 1000L

  val block: ObftBlock = ObftBlock(header, body)
}
