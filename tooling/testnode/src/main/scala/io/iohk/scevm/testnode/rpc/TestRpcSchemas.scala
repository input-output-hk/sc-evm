package io.iohk.scevm.testnode.rpc

import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{ObftGenesisAccount, UInt256}
import io.iohk.scevm.rpc.armadillo.CommonRpcSchemas
import io.iohk.scevm.testnode.TestJRpcController
import io.iohk.scevm.testnode.TestJRpcController._
import sttp.tapir.{Schema, Validator}

trait TestRpcSchemas extends CommonRpcSchemas {
  implicit val schemaForBlockCount: Schema[BlockCount] =
    Schema.schemaForInt.validate(Validator.positive).map(i => Some(BlockCount(i)))(blockCount => blockCount.number)
  implicit val schemaForTimestamp: Schema[Timestamp] =
    Schema.schemaForLong.validate(Validator.positiveOrZero).map(l => Some(Timestamp(l)))(timestamp => timestamp.value)
  implicit val schemaForGenesisParams: Schema[GenesisParams]       = Schema.derived
  implicit val schemaForBlockchainParams: Schema[BlockchainParams] = Schema.derived
  implicit val schemaForSealEngineType: Schema[SealEngineType]     = Schema.derived
  implicit val schemaForUInt256: Schema[UInt256]                   = Schema.schemaForBigInt.map(x => Some(UInt256.apply(x)))(_.toBigInt)
  implicit val schemaForMapUInt256ToUInt256: Schema[Map[UInt256, UInt256]] =
    Schema.schemaForMap[UInt256, UInt256](_.toString)
  implicit val schemaForObftGenesisAccount: Schema[ObftGenesisAccount] = Schema.derived
  implicit val schemaForMapByteStringToObftGenesisAccount: Schema[Map[ByteString, ObftGenesisAccount]] =
    Schema.schemaForMap[ByteString, ObftGenesisAccount](Hex.toHexString(_))
  implicit val schemaForChainParams: Schema[ChainParams] = Schema.derived

  implicit val schemaForAddressHash: Schema[AddressHash] =
    schemaForByteString
      .validate(
        Validator.fixedSize[Byte, IndexedSeq](TestJRpcController.AddressHashLength).contramap(identity)
      )
      .as[AddressHash]
  implicit val schemaForMapByteStringToByteString: Schema[Map[ByteString, ByteString]] =
    Schema.schemaForMap[ByteString, ByteString](Hex.toHexString(_))
  implicit val schemaForAccountsInRangeResponse: Schema[AccountsInRangeResponse] = Schema.derived

  implicit val schemaForStorageRangeParams: Schema[StorageRangeParams]             = Schema.derived
  implicit val schemaStorageEntry: Schema[StorageEntry]                            = Schema.derived
  implicit val schemaForMapStringToStorageEntry: Schema[Map[String, StorageEntry]] = Schema.schemaForMap[StorageEntry]
  implicit val schemaForStorageRangeResponse: Schema[StorageRangeResponse]         = Schema.derived
}

object TestRpcSchemas extends TestRpcSchemas
