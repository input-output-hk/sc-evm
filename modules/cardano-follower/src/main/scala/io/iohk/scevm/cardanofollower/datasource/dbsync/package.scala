package io.iohk.scevm.cardanofollower.datasource

import cats.Show
import cats.data.NonEmptyList
import doobie.Meta
import doobie.postgres.implicits._
import doobie.util.{Get, Read}
import io.circe.parser._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.ByteUtils
import io.iohk.scevm.cardanofollower.datasource.dbsync.CirceDatumAdapter._
import io.iohk.scevm.domain.{BlockHash, Token}
import io.iohk.scevm.plutus.Datum
import io.iohk.scevm.trustlesssidechain.cardano._
import org.postgresql.util.PGobject

import java.time.Instant

package object dbsync {
  implicit val assetNameMeta: Meta[AssetName] =
    Meta.ByteArrayMeta.timap(bytes => AssetName(ByteString(bytes)))(_.value.toArray)
  implicit val mainchainEpochMeta: Meta[MainchainEpoch]     = Meta.LongMeta.timap(MainchainEpoch.apply)(_.number)
  implicit val mainchainAddressMeta: Meta[MainchainAddress] = Meta.StringMeta.timap(MainchainAddress.apply)(_.value)
  implicit val mainchainBlockNumberMeta: Meta[MainchainBlockNumber] =
    Meta.LongMeta.timap(MainchainBlockNumber.apply)(_.value)
  implicit val mainchainTxHashMeta: Meta[MainchainTxHash] =
    Meta.ByteArrayMeta.timap(bytes => MainchainTxHash(ByteString(bytes)))(_.value.toArray)
  implicit val mainchainSlotMeta: Meta[MainchainSlot] =
    Meta.LongMeta.timap(MainchainSlot.apply)(_.number)
  implicit val policyIdMeta: Meta[PolicyId] =
    Meta.ByteArrayMeta.timap(s => PolicyId(ByteString(s)))(g => g.value.toArray)
  implicit val blockHashMeta: Meta[BlockHash] =
    Meta.ByteArrayMeta.timap(bytes => BlockHash(ByteString(bytes)))(_.byteString.toArray)
  implicit val tokenMeta: Meta[Token] = Meta.LongMeta.timap(i => Token.apply(i))(_.value.toLong)

  implicit val entropyGet: Get[EpochNonce] =
    Get[Array[Byte]].map(bytes => EpochNonce(ByteUtils.toBigInt(ByteString(bytes))))
  implicit val blake2bGet: Get[Blake2bHash28] =
    Get[Array[Byte]].map(bytes => Blake2bHash28(ByteString(bytes)))
  implicit val lovelaceGet: Get[Lovelace]   = Get[Long].map(long => Lovelace(long))
  implicit val showPgObject: Show[PGobject] = Show.fromToString

  implicit val DatumGet: Get[Datum] = Get.Advanced.other[PGobject](NonEmptyList.of("json")).temap[Datum] { o =>
    decode[Datum](o.getValue).left.map(_.getMessage)
  }

  implicit val mainchainBlockInfoRead: Read[MainchainBlockInfo] =
    Read[(MainchainBlockNumber, BlockHash, MainchainEpoch, MainchainSlot, Instant)].map(
      (MainchainBlockInfo.apply _).tupled
    )

  implicit val SpentByInfoRead: Read[SpentByInfo] =
    Read[(MainchainTxHash, MainchainEpoch, MainchainSlot)].map((SpentByInfo.apply _).tupled)

}
