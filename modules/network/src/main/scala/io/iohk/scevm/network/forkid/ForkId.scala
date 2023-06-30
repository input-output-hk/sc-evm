package io.iohk.scevm.network.forkid

import io.iohk.ethereum.rlp._
import io.iohk.ethereum.utils.ByteUtils._
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.domain.BlockNumber._
import io.iohk.scevm.domain.{BlockHash, BlockNumber}
import io.iohk.scevm.utils.BigIntExtensionMethods._

import java.util.zip.CRC32

import RLPImplicitConversions._

final case class ForkId(hash: BigInt, next: Option[BlockNumber]) {
  override def toString(): String = s"ForkId(0x${Hex.toHexString(hash.toUnsignedByteArray)}, $next)"
}

object ForkId {

  def create(genesisHash: BlockHash, config: BlockchainConfig)(head: BlockNumber): ForkId = {
    val crc = new CRC32()
    crc.update(genesisHash.byteString.asByteBuffer)
    val next = gatherForks(config).find { fork =>
      if (fork <= head) {
        crc.update(bigIntToBytes(fork.value, 8))
      }
      fork > head
    }
    new ForkId(crc.getValue(), next)
  }

  val noFork: BlockNumber = BlockNumber(BigInt("1000000000000000000"))

  def gatherForks(config: BlockchainConfig): List[BlockNumber] =
    config.forkBlockNumbers.all
      .filterNot(v => v == BlockNumber(0) || v == noFork)
      .distinct
      .sorted

  implicit class ForkIdEnc(forkId: ForkId) extends RLPSerializable {
    import RLPImplicits._

    import io.iohk.ethereum.utils.ByteUtils._
    override def toRLPEncodable: RLPEncodeable = {
      val hash: Array[Byte] = bigIntToBytes(forkId.hash, 4).takeRight(4)
      val next: Array[Byte] = bigIntToUnsignedByteArray(forkId.next.getOrElse(BlockNumber(0)).value).takeRight(8)
      RLPList(hash, next)
    }

  }

  implicit val forkIdEnc: RLPDecoder[ForkId] = new RLPDecoder[ForkId] {

    def decode(rlp: RLPEncodeable): ForkId = rlp match {
      case RLPList(hash, next) =>
        val i = bigIntFromEncodeable(next)
        ForkId(bigIntFromEncodeable(hash), if (i == 0) None else Some(BlockNumber(i)))
      case undecoded => throw RLPException("Error when decoding ForkId", undecoded)
    }
  }
}
