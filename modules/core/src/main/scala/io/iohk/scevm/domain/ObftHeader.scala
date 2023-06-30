package io.iohk.scevm.domain

import cats.Show
import cats.implicits.showInterpolator
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.kec256
import io.iohk.ethereum.rlp._
import io.iohk.ethereum.utils.ByteStringUtils.RichByteString
import io.iohk.scevm.utils.SystemTime
import io.iohk.scevm.utils.SystemTime.UnixTimestamp

/** Block header structure for OBFT consensus
  * @param parentHash pointer to the parent block
  * @param slotNumber number of the slot
  * @param beneficiary address of the account to be rewarded for the creation of the block
  * @param stateRoot pointer to the root of the state MPT
  * @param transactionsRoot pointer to the root of the transactions MPT
  * @param receiptsRoot pointer to the root of the receipts MPT
  * @param logsBloom data-structure to know whether a contract has been logged
  * @param number number representing the sequence of blocks, may increase more slowly than slotNumber (not required but useful)
  * @param gasLimit the maximum amount of gas that could be spent
  * @param gasUsed the total amount of gas used
  * @param unixTimestamp the local time (not required but might be used for debug or logical-clock PoC)
  * @param publicSigningKey the public side of the key used to sign the block (not required but might be useful for quick validation)
  * @param signature the result of this signed block with the private key
  */
final case class ObftHeader(
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
    publicSigningKey: SidechainPublicKey,
    signature: SidechainSignature
) {

  def isParentOf(child: ObftHeader): Boolean =
    number.next == child.number && child.parentHash == hash

  def containsTransactions: Boolean = {
    import io.iohk.scevm.mpt.MerklePatriciaTrie
    transactionsRoot != ByteString(MerklePatriciaTrie.EmptyRootHash)
  }

  /** Calculates blockHash for given block header
    *
    * @return - hash that can be used to get block bodies / receipts
    */
  lazy val hash: BlockHash =
    BlockHash(ByteString(kec256(encode(this): Array[Byte])))

  lazy val hashWithoutSignature: ByteString =
    ObftHeaderUnsigned.fromSigned(this).hash

  def idTag: String =
    show"BlockTag(number=${number.value}, slot=${slotNumber.number}, hash=${hash.toHex})"

  def fullToString: String =
    s"ObftHeader { " +
      s"hash: $hash, " +
      s"parentHash: ${parentHash.toHex}, " +
      s"number: $number, " +
      s"slotNumber: ${slotNumber.number}, " +
      s"beneficiary: $beneficiary, " +
      s"stateRoot: ${stateRoot.toHex}, " +
      s"transactionsRoot: ${transactionsRoot.toHex}, " +
      s"receiptsRoot: ${receiptsRoot.toHex}, " +
      s"logsBloom: ${logsBloom.toHex}, " +
      s"gasLimit: $gasLimit, " +
      s"gasUsed: $gasUsed, " +
      s"unixTimestamp: $unixTimestamp, " +
      s"publicSigningKey: $publicSigningKey, " +
      s"signature: ${signature.toBytes.toHex} " +
      s"}"

}

object ObftHeader {
  implicit val show: Show[ObftHeader] = Show.show(_.idTag)

  import io.iohk.ethereum.rlp.RLPImplicitConversions._
  import io.iohk.ethereum.rlp.RLPImplicits._

  implicit val signatureRLPCodec: RLPCodec[SidechainSignature] =
    RLPCodec.instance(
      _.toBytes,
      signature =>
        SidechainSignature.fromBytes(signature) match {
          case Right(signature) => signature
          case Left(reason)     => throw new Exception(s"ObftHeader cannot be decoded: $reason")
        }
    )

  implicit val rlpCodec: RLPCodec[ObftHeader] =
    RLPCodec.instance(
      {
        case ObftHeader(
              parentHash,
              number,
              slotNumber,
              beneficiary,
              stateRoot,
              transactionsRoot,
              receiptsRoot,
              logsBloom,
              gasLimit,
              gasUsed,
              unixTimestamp,
              publicSigningKey,
              signature
            ) =>
          RLPList(
            parentHash.byteString,
            number,
            slotNumber,
            beneficiary,
            stateRoot,
            transactionsRoot,
            receiptsRoot,
            logsBloom,
            gasLimit,
            gasUsed,
            unixTimestamp,
            publicSigningKey,
            signature
          )
      },
      {
        case RLPList(
              parentHash,
              number,
              slotNumber,
              beneficiary,
              stateRoot,
              transactionsRoot,
              receiptsRoot,
              logsBloom,
              gasLimit,
              gasUsed,
              unixTimestamp,
              publicSigningKey,
              signature
            ) =>
          ObftHeader(
            BlockHash(parentHash),
            BlockNumber(number),
            decode[Slot](slotNumber),
            decode[Address](beneficiary),
            stateRoot,
            transactionsRoot,
            receiptsRoot,
            logsBloom,
            gasLimit,
            gasUsed,
            decode[UnixTimestamp](unixTimestamp),
            SidechainPublicKey.fromBytesUnsafe(publicSigningKey),
            decode[SidechainSignature](signature)
          )
      }
    )
}
