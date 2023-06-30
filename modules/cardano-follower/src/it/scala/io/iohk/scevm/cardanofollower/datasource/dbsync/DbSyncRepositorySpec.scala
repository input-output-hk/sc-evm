package io.iohk.scevm.cardanofollower.datasource.dbsync

import com.softwaremill.diffx.generic.auto.diffForCaseClass
import com.softwaremill.diffx.scalatest.DiffShouldMatcher
import com.softwaremill.diffx.{ObjectMatcher, SeqMatcher}
import doobie.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.cardanofollower.datasource.MainchainDataRepository.DbIncomingCrossChainTransaction
import io.iohk.scevm.domain.{BlockHash, Token}
import io.iohk.scevm.plutus.{ByteStringDatum, ConstructorDatum, IntegerDatum, ListDatum}
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import io.iohk.scevm.trustlesssidechain.cardano._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{EitherValues, OptionValues}

import java.time.Instant

class DbSyncRepositorySpec
    extends AnyWordSpec
    with DBIntegrationSpec
    with IOSupport
    with OptionValues
    with EitherValues
    with ScalaFutures
    with NormalPatience
    with DiffShouldMatcher
    with Matchers {

  import DbSyncRepositorySpec._

  implicit val mainchainTxOutputVectorMatcher: SeqMatcher[MainchainTxOutput] =
    ObjectMatcher.seq[MainchainTxOutput].byValue(_.id)

  "should read and decode epoch nonce" in withMigrations(
    Migrations.EpochParams,
    Migrations.EpochPools,
    Migrations.EpochStake
  ) {
    val nonce = DbSyncRepository.getEpochNonce(MainchainEpoch(190)).transact(currentDb.xa).ioValue
    nonce.value shouldMatchTo EpochNonce(
      BigInt("77767497351609740808791974902407088128725460654185244904577174913945395979250")
    )
  }

  "should read stake distribution" in withMigrations(
    Migrations.EpochParams,
    Migrations.EpochPools,
    Migrations.EpochStake
  ) {
    val stakeDistribution = DbSyncRepository.getStakeDistribution(MainchainEpoch(189)).transact(currentDb.xa).ioValue
    stakeDistribution shouldMatchTo Map(
      Blake2bHash28.fromHex("c3d5464dfd3344c75ae1dd9a2ef2cbc2208f543716f721cb499840fe").value -> Lovelace(
        5001995651486L
      ),
      Blake2bHash28.fromHex("4d67a0e4ed5c9a2ca822b757c44ab855ecd4a5ddfbdbd8ec60eec372").value -> Lovelace(
        1001995478725L
      ),
      Blake2bHash28.fromHex("4260b0d9ec43890ee413d2e0bdb00f2d57c978e1a4c143bc65f0e38e").value -> Lovelace(
        123456789L
      )
    )
  }

  "getUtxosForAddress" should {
    val testAddr = MainchainAddress("get_utxo_test_address")
    val tx1Hash  = MainchainTxHash.decodeUnsafe("0000000000000000000000000000000000000000000000000000000000001001")
    val tx2Hash  = MainchainTxHash.decodeUnsafe("0000000000000000000000000000000000000000000000000000000000001002")

    val int1datum  = ConstructorDatum(0, Vector(IntegerDatum(1)))
    val int42datum = ConstructorDatum(0, Vector(IntegerDatum(42)))

    val utxo1 = MainchainTxOutput(
      UtxoId(tx1Hash, 0),
      MainchainBlockNumber(0),
      MainchainSlot(51278410),
      MainchainEpoch(189),
      3,
      testAddr,
      None,
      Some(SpentByInfo(tx2Hash, MainchainEpoch(190), MainchainSlot(51710500))),
      List.empty
    )
    val utxo2 = MainchainTxOutput(
      UtxoId(tx1Hash, 1),
      MainchainBlockNumber(0),
      MainchainSlot(51278410),
      MainchainEpoch(189),
      3,
      testAddr,
      None,
      Some(SpentByInfo(tx2Hash, MainchainEpoch(190), MainchainSlot(51710500))),
      List.empty
    )
    val utxo3 = MainchainTxOutput(
      UtxoId(tx1Hash, 2),
      MainchainBlockNumber(0),
      MainchainSlot(51278410),
      MainchainEpoch(189),
      3,
      testAddr,
      Some(int42datum),
      None,
      List.empty
    )
    val utxo4 = MainchainTxOutput(
      UtxoId(tx1Hash, 3),
      MainchainBlockNumber(0),
      MainchainSlot(51278410),
      MainchainEpoch(189),
      3,
      testAddr,
      Some(int1datum),
      None,
      List.empty
    )
    val utxo5 = MainchainTxOutput(
      UtxoId(tx2Hash, 0),
      MainchainBlockNumber(2),
      MainchainSlot(51710500),
      MainchainEpoch(190),
      2,
      testAddr,
      Some(int1datum),
      None,
      List(
        UtxoId.parseUnsafe("0000000000000000000000000000000000000000000000000000000000001001#0"),
        UtxoId.parseUnsafe("0000000000000000000000000000000000000000000000000000000000001001#1")
      )
    )

    "correctly read UTXOs" in withMigrations(
      Migrations.Blocks,
      Migrations.Transactions
    ) {
      val utxoAfterBlock0 =
        DbSyncRepository.getUtxosForAddress(testAddr, MainchainBlockNumber(0)).transact(currentDb.xa).ioValue
      utxoAfterBlock0 shouldMatchTo Vector(utxo1, utxo2, utxo3, utxo4)
    }

    "correctly read UTXOs when some outputs have been consumed" in withMigrations(
      Migrations.Blocks,
      Migrations.Transactions
    ) {
      val utxoAfterBlock2 =
        DbSyncRepository.getUtxosForAddress(testAddr, MainchainBlockNumber(2)).transact(currentDb.xa).ioValue
      utxoAfterBlock2 shouldMatchTo Vector(utxo3, utxo4, utxo5)
    }

    "correctly read UTXOs created after a given block" in withMigrations(
      Migrations.Blocks,
      Migrations.Transactions
    ) {
      val utxoAfterBlock3 =
        DbSyncRepository
          .getUtxosForAddress(testAddr, MainchainBlockNumber(2), createdAfterBlock = Some(MainchainBlockNumber(0)))
          .transact(currentDb.xa)
          .ioValue
      utxoAfterBlock3 shouldMatchTo Vector(utxo5)
    }
  }

  "should get latest block" in withMigrations(
    Migrations.Blocks
  ) {
    val latestBlock = DbSyncRepository.getLatestBlockInfo.transact(currentDb.xa).ioValue

    latestBlock shouldMatchTo Some(
      MainchainBlockInfo(
        MainchainBlockNumber(5),
        BlockHash(
          ByteString(Hex.decodeAsArrayUnsafe("ebeed7fb0067f14d6f6436c7f7dedb27ce3ceb4d2d18ff249d43b22d86fae3f1"))
        ),
        MainchainEpoch(193),
        MainchainSlot(53006500),
        Instant.parse("2022-04-26T16:29:00Z")
      )
    )

  }

  "should get block for a given block number" in withMigrations(Migrations.Blocks) {
    val block = DbSyncRepository.getBlockInfoForNumber(MainchainBlockNumber(3)).transact(currentDb.xa).ioValue
    block shouldMatchTo Some(
      MainchainBlockInfo(
        MainchainBlockNumber(3),
        BlockHash(
          ByteString(Hex.decodeAsArrayUnsafe("CBEED7FB0067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1"))
        ),
        MainchainEpoch(191),
        MainchainSlot(52142500),
        Instant.parse("2022-04-24T16:29:00Z")
      )
    )
  }

  "should get the closestBlock lower or equal to a slot in" in withMigrations(
    Migrations.Blocks
  ) {
    val blockInfo =
      DbSyncRepository
        .getLatestBlockForSlot(MainchainSlot(51710499))
        .transact(currentDb.xa)
        .ioValue

    blockInfo shouldMatchTo Some(
      MainchainBlockInfo(
        MainchainBlockNumber(1),
        BlockHash(
          ByteString(Hex.decodeAsArrayUnsafe("abeed7fb0067f14d6f6436c7f7dedb27ce3ceb4d2d18ff249d43b22d86fae3f1"))
        ),
        MainchainEpoch(190),
        MainchainSlot(51710400),
        Instant.parse("2022-04-22T16:29:00Z")
      )
    )
  }

  "get Mainchain to Sidechain transactions" in withMigrations(Migrations.Blocks, Migrations.Transactions) {
    val fromSlot        = MainchainSlot(52142500)
    val blockUpperBound = MainchainBlockNumber(3)
    val result = DbSyncRepository
      .getCrossChainTransactions(fromSlot, blockUpperBound, Asset(McToScScriptAddress, FuelAssetName))
      .transact(currentDb.xa)
      .ioValue

    result shouldMatchTo Vector(
      expectedMainchainToSidechainTransaction1,
      expectedMainchainToSidechainTransaction2
    )
  }

  "get the block for UTxO" in withMigrations(Migrations.Blocks, Migrations.Transactions) {
    val result = DbSyncRepository.getSlotForTxHash(tx1Hash).transact(currentDb.xa).ioValue
    result should be(Some(MainchainSlot(51278410)))

    DbSyncRepository
      .getSlotForTxHash(MainchainTxHash(ByteString("abc")))
      .transact(currentDb.xa)
      .ioValue should be(None)
  }

  "get an NFT" in withMigrations(Migrations.Blocks, Migrations.Transactions) {
    val result = DbSyncRepository
      .getNftUtxo(
        Asset(nftPolicyId, AssetName.empty)
      )
      .transact(currentDb.xa)
      .ioValue

    result shouldBe Some(currentCommitteeNft)
  }

  "return last mint utxo action for a given policy" in withMigrations(Migrations.Blocks, Migrations.MerkleRoots) {
    val result = DbSyncRepository
      .getLatestMintAction(
        PolicyId.fromHexUnsafe("73eafe87b46e2ae14e6ffa1ddc1525fd8169892a65126a08a732ba0e")
      )
      .transact(currentDb.xa)
      .ioValue

    result shouldBe Some(
      MintAction(
        MainchainTxHash.decodeUnsafe("000000010077F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F2"),
        AssetName.fromHexUnsafe("bdbd8d6191322e4e3a1788e1496d13fbdd9252e319e8e45372f65a068c5c3c2b"),
        MainchainBlockNumber(4),
        2,
        Some(
          ConstructorDatum(
            constructor = 0,
            fields = Vector(
              ByteStringDatum(Hex.decodeUnsafe("bdbd8d6191322e4e3a1788e1496d13fbdd9252e319e8e45372f65a068c5c3c2b")),
              ConstructorDatum(
                constructor = 0,
                fields = Vector(
                  ByteStringDatum(bytes =
                    Hex.decodeUnsafe("bdbd8d6191322e4e3a1788e1496d13fbdd9252e319e8e45372f65a068c5c3c2a")
                  )
                )
              ),
              ListDatum(list =
                Vector(
                  ByteStringDatum(bytes =
                    Hex.decodeUnsafe(
                      "0fb3c5700d5b75daad75e4bc57ea96e0f09132812b1d8f33c53d697deaeaefcd5c472f252cb9219ee7cc7528d755b2a825fd00788c333a60de2f264ef00ae53f"
                    )
                  ),
                  ByteStringDatum(bytes =
                    Hex.decodeUnsafe(
                      "312b0b8a5177a21280a6f316896781aa72ef8b6a6236b8fd70bbbe8407e7950805b7e3e583ca3d65071b6ebb5b20c1c408f4acd2006a05117b38f6d6ce019415"
                    )
                  ),
                  ByteStringDatum(bytes =
                    Hex.decodeUnsafe(
                      "53f4991b567497e32d77dfc48ca08f4720f070fe85fe9dfebcf7f4ef85fc1fdc00886aeccd10649e8d57a01708a42980f8d38f09829755b2b0541da727931b73"
                    )
                  )
                )
              ),
              ListDatum(list =
                Vector(
                  ByteStringDatum(bytes =
                    Hex.decodeUnsafe("0239bdbb177c51cd405659e39439a7fd65bc377c5b24a4684d335518fc5b618a16")
                  ),
                  ByteStringDatum(bytes =
                    Hex.decodeUnsafe("02d552ed9b1e49e243baf80603475f88a0dc1cbbdcc5f66afe171c021bb9705340")
                  ),
                  ByteStringDatum(bytes =
                    Hex.decodeUnsafe("02de4771f6630b8c6e945a604b3d91a9d266b78fef7c6b6ef72cead11e608e6eb9")
                  )
                )
              )
            )
          )
        )
      )
    )
  }
}

object DbSyncRepositorySpec extends DbSyncFixtures {

  val expectedMainchainToSidechainTransaction1: DbIncomingCrossChainTransaction = DbIncomingCrossChainTransaction(
    datum = ConstructorDatum(0, Vector(ByteStringDatum(Hex.decodeUnsafe("CC95F2A1011728FC8B861B3C9FEEFBB4E7449B98")))),
    value = Token(-500),
    txHash = xcTxHash1,
    blockNumber = MainchainBlockNumber(3)
  )

  val expectedMainchainToSidechainTransaction2: DbIncomingCrossChainTransaction = DbIncomingCrossChainTransaction(
    datum = ConstructorDatum(0, Vector(ByteStringDatum(Hex.decodeUnsafe("CC95F2A1011728FC8B861B3C9FEEFBB4E7449B98")))),
    value = Token(-500),
    txHash = xcTxHash2,
    blockNumber = MainchainBlockNumber(3)
  )

  val previousCommitteeNft: NftTxOutput = NftTxOutput(
    id = UtxoId(MainchainTxHash.decodeUnsafe("000000010067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1"), 0),
    epoch = MainchainEpoch(191),
    blockNumber = MainchainBlockNumber(3),
    slot = MainchainSlot(52142500),
    txIndexWithinBlock = 2,
    datum = Some(
      ConstructorDatum(
        0,
        Vector(
          ByteStringDatum(Hex.decodeUnsafe("ffff2cd23dcd12169df205f8bf659554441885fb39393a82a5e3b13601aa8cab")),
          IntegerDatum(1113)
        )
      )
    )
  )
  val currentCommitteeNft: NftTxOutput = NftTxOutput(
    id = UtxoId(MainchainTxHash.decodeUnsafe("000000020067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F2"), 0),
    epoch = MainchainEpoch(192),
    blockNumber = MainchainBlockNumber(4),
    slot = MainchainSlot(52574500),
    txIndexWithinBlock = 2,
    datum = Some(
      ConstructorDatum(
        0,
        Vector(
          ByteStringDatum(Hex.decodeUnsafe("d5462cd23dcd12169df205f8bf659554441885fb39393a82a5e3b13601aa8cab")),
          IntegerDatum(1114)
        )
      )
    )
  )

}
