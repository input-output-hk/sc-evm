package io.iohk.scevm.mpt

import io.iohk.bytes.ByteString
import io.iohk.ethereum.rlp
import io.iohk.ethereum.rlp.RLPImplicits._
import io.iohk.ethereum.utils.ByteUtils
import io.iohk.scevm.mpt.MptStorage
import io.iohk.scevm.serialization.{ByteArrayEncoder, ByteArraySerializable}
import org.bouncycastle.util.BigIntegers

object EthereumUInt256Mpt {
  val BigIntSerializer256Bits: ByteArrayEncoder[BigInt] = new ByteArrayEncoder[BigInt] {
    override def toBytes(input: BigInt): Array[Byte] =
      ByteUtils.padLeft(ByteString(BigIntegers.asUnsignedByteArray(input.bigInteger)), 32).toArray[Byte]
  }

  val rlpBigIntSerializer: ByteArraySerializable[BigInt] = new ByteArraySerializable[BigInt] {
    override def fromBytes(bytes: Array[Byte]): BigInt = rlp.decode[BigInt](bytes)
    override def toBytes(input: BigInt): Array[Byte]   = rlp.encode[BigInt](input)
  }

  def storageMpt(rootHash: ByteString, mptStorage: MptStorage): MerklePatriciaTrie[BigInt, BigInt] =
    MerklePatriciaTrie[BigInt, BigInt](rootHash.toArray[Byte], mptStorage)(
      HashByteArraySerializable(BigIntSerializer256Bits),
      rlpBigIntSerializer
    )
}
