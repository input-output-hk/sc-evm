package io.iohk.scevm.extvm

import com.google.protobuf.{ByteString => ProtoByteString}
import io.iohk.bytes.ByteString
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{Address, BlockNumber, UInt256}

import scala.language.implicitConversions

object Implicits {

  implicit def byteStringToProtoByteString(b: ByteString): ProtoByteString =
    ProtoByteString.copyFrom(b.toArray)

  implicit def addressToProtoByteString(a: Address): ProtoByteString =
    ProtoByteString.copyFrom(a.toArray)

  implicit def chainIdToProtoByteString(chainId: ChainId): ProtoByteString =
    ProtoByteString.copyFrom(Array(chainId.value))

  implicit def uInt256ToProtoByteString(u: UInt256): ProtoByteString =
    u.toBigInt

  implicit def bigintToProtoByteString(b: BigInt): ProtoByteString =
    ProtoByteString.copyFrom(b.toByteArray)

  implicit def blockNumberToProtoByteString(bn: BlockNumber): ProtoByteString =
    bigintToProtoByteString(bn.value)

  implicit def protoByteStringToByteString(gb: ProtoByteString): ByteString =
    ByteString(gb.toByteArray)

  implicit def protoByteStringToAddress(gb: ProtoByteString): Address =
    Address(gb.toByteArray)

  implicit def protoByteStringToUInt256(gb: ProtoByteString): UInt256 =
    UInt256(gb: BigInt)

  implicit def protoByteStringToBigInt(gb: ProtoByteString): BigInt =
    BigInt(gb.toByteArray)

  implicit def protoByteStringToBlockNumber(gb: ProtoByteString): BlockNumber =
    BlockNumber(protoByteStringToBigInt(gb))
}
