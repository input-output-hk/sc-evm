package io.iohk.scevm.cardanofollower.datasource.dbsync

import cats.data.NonEmptyList
import cats.effect.IO
import com.softwaremill.diffx.generic.auto.diffForCaseClass
import com.softwaremill.diffx.scalatest.DiffShouldMatcher
import com.softwaremill.diffx.{ObjectMatcher, SeqMatcher}
import com.softwaremill.quicklens._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{AbstractSignatureScheme, ECDSA, ECDSASignatureNoRecovery, EdDSA}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.cardanofollower.CardanoFollowerConfig
import io.iohk.scevm.cardanofollower.datasource.{
  DataSource,
  EpochData,
  LeaderCandidate,
  PendingDeregistration,
  PendingRegistration,
  PendingRegistrationChange,
  RegistrationData
}
import io.iohk.scevm.cardanofollower.plutus.TransactionRecipientDatum
import io.iohk.scevm.domain.{BlockHash, BlockNumber, Token}
import io.iohk.scevm.sidechain.{IncomingCrossChainTransaction, SidechainEpoch}
import io.iohk.scevm.testing.IOSupport
import io.iohk.scevm.trustlesssidechain.cardano._
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{EitherValues, OptionValues}

import scala.concurrent.duration.DurationInt

class DbSyncDataSourceSpec
    extends AnyWordSpec
    with DBIntegrationSpec
    with IOSupport
    with OptionValues
    with EitherValues
    with ScalaFutures
    with IntegrationPatience
    with DiffShouldMatcher
    with Matchers {
  implicit def objectFieldMatcher[Scheme <: AbstractSignatureScheme]: SeqMatcher[LeaderCandidate[Scheme]] =
    ObjectMatcher.seq[LeaderCandidate[Scheme]].byValue(_.mainchainPubKey)

  class Fixtures(stabilityParameter: Long) extends DbSyncFixtures {

    val committeeNftAsset: Asset =
      Asset.fromHexUnsafe("636f6d6d697474656586f064cd3497bc176e1ca51d3d7de836db5571", "")

    val checkpointNftAsset: Asset =
      Asset.fromHexUnsafe("636f6d6d697474656586f064cd3497bc176e1ca51d3d7de836db5572", "")

    val referencedMerkleRootNftPolicy: PolicyId =
      PolicyId.fromHexUnsafe("73eafe87b46e2ae14e6ffa1ddc1525fd8169892a65126a08a732ba0e")
    val unreferencedMerkleRootNftPolicy: PolicyId =
      PolicyId.fromHexUnsafe("636f6d6d697474656586f064cd3497bc176e1ca51d3d7de836db2222")

    val cardanoFollowerConfig: CardanoFollowerConfig = CardanoFollowerConfig(
      stabilityParameter = stabilityParameter,
      firstEpochTimestamp = 1596399616000L.millisToTs,
      firstEpochNumber = 75,
      firstSlot = MainchainSlot(2030400),
      epochDuration = 432000.seconds,
      slotDuration = 1.seconds,
      activeSlotCoeff = BigDecimal("0.05"),
      committeeCandidateAddress = MainchainAddress("script_addr"),
      fuelMintingPolicyId = McToScScriptAddress,
      fuelAssetName = FuelAssetName,
      merkleRootNftPolicyId = unreferencedMerkleRootNftPolicy,
      committeeNftPolicyId = committeeNftAsset.policy,
      checkpointNftPolicyId = checkpointNftAsset.policy
    )

    val xcTx1: IncomingCrossChainTransaction = IncomingCrossChainTransaction(
      recipientDatum = TransactionRecipientDatum(tx1RecipientAddress),
      value = Token(500),
      txId = xcTxHash1,
      MainchainBlockNumber(3 + stabilityParameter)
    )

    val xcTx2: IncomingCrossChainTransaction = IncomingCrossChainTransaction(
      recipientDatum = TransactionRecipientDatum(tx1RecipientAddress),
      value = Token(500),
      txId = xcTxHash2,
      MainchainBlockNumber(3 + stabilityParameter)
    )

    val xcTx3: IncomingCrossChainTransaction = IncomingCrossChainTransaction(
      recipientDatum = TransactionRecipientDatum(tx1RecipientAddress),
      value = Token(500),
      txId = xcTxHash3,
      MainchainBlockNumber(4 + stabilityParameter)
    )

    val candidate1FromBlock0: LeaderCandidate[ECDSA] = candidateWithStake(
      mainchainPubKeyHex = mainchainPubKeyHex1,
      sidechainPubKeyHex = sidechainPubKeyHex1,
      mainchainSignatureHex = mainchainSignatureHex1,
      sidechainSignatureHex = sidechainSignatureHex1,
      stake = Lovelace(1001995478725L),
      utxoIndex = 1,
      consumedUtxo = UtxoId.parseUnsafe("cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13#1"),
      txInputs = List(
        UtxoId.parseUnsafe("cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13#0"),
        UtxoId.parseUnsafe("cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13#1")
      ),
      isPending = false,
      mainchainTxHash = tx1Hash,
      mainchainEpoch = MainchainEpoch(189),
      mainchainBlockNumber = MainchainBlockNumber(0),
      mainchainSlot = MainchainSlot(51278410),
      txIndexWithinBlock = 1,
      pendingRegistrationChange = None
    )

    val candidate1FromBlock0WithDeregistration: LeaderCandidate[ECDSA] = candidate1FromBlock0
      .modify(_.registrations.head.pendingChange)
      .setTo(Some(PendingDeregistration(tx2Hash, MainchainEpoch(191))))

    val candidate2FromBlock0: LeaderCandidate[ECDSA] = candidateWithStake(
      mainchainPubKeyHex = mainchainPubKeyHex2,
      sidechainPubKeyHex = sidechainPubKeyHex1,
      mainchainSignatureHex = mainchainSignatureHex1,
      sidechainSignatureHex = sidechainSignatureHex1,
      stake = Lovelace(5001995651486L),
      utxoIndex = 3,
      consumedUtxo = UtxoId.parseUnsafe("cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13#1"),
      txInputs = List(
        UtxoId.parseUnsafe("cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13#0"),
        UtxoId.parseUnsafe("cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13#1")
      ),
      isPending = false,
      mainchainTxHash = tx1Hash,
      mainchainEpoch = MainchainEpoch(189),
      mainchainBlockNumber = MainchainBlockNumber(0),
      mainchainSlot = MainchainSlot(51278410),
      txIndexWithinBlock = 1
    )

    val candidate1FromBlock2: LeaderCandidate[ECDSA] = {
      val candidate = candidateWithStake(
        mainchainPubKeyHex = mainchainPubKeyHex3,
        sidechainPubKeyHex = sidechainPubKeyHex3,
        mainchainSignatureHex = mainchainSignatureHex3,
        sidechainSignatureHex = sidechainSignatureHex3,
        stake = Lovelace(123456789L),
        utxoIndex = 0,
        consumedUtxo = UtxoId.parseUnsafe("cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13#2"),
        txInputs = List(
          UtxoId.parseUnsafe("cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13#2")
        ),
        isPending = true,
        mainchainTxHash = tx5Hash,
        mainchainEpoch = MainchainEpoch(190),
        mainchainBlockNumber = MainchainBlockNumber(2),
        mainchainSlot = MainchainSlot(51710500),
        txIndexWithinBlock = 1,
        pendingRegistrationChange = Some(PendingRegistration(txHash = tx5Hash, effectiveAt = MainchainEpoch(191)))
      )
      val registrationData = candidate.registrations.head
      val otherRegistrationData = registrationData.copy(
        consumedInput = UtxoId.parseUnsafe("bfbee74ab533f40979101057f96de62e95233f2a5216eb16b54106f09fd7350d#2"),
        utxoInfo = registrationData.utxoInfo.copy(utxoId = UtxoId(tx5Hash, 1))
      )
      candidate.copy(registrations = candidate.registrations :+ otherRegistrationData)
    }

    val epoch190Data: EpochData[ECDSA] = EpochData(epoch189Nonce, List(candidate1FromBlock0, candidate2FromBlock0))

    val currentCheckpointNft: CheckpointNft = CheckpointNft(
      utxoInfo = UtxoInfo(
        UtxoId(MainchainTxHash.decodeUnsafe("100000010067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1"), 0),
        MainchainEpoch(193),
        MainchainBlockNumber(5),
        MainchainSlot(53006500),
        2
      ),
      sidechainBlockHash =
        BlockHash(Hex.decodeUnsafe("abcd2cd23dcd12169df205f8bf659554441885fb39393a82a5e3b13601aa8cab")),
      sidechainBlockNumber = BlockNumber(667)
    )

    val currentCommitteeNft: CommitteeNft = CommitteeNft(
      utxoInfo = UtxoInfo(
        UtxoId(MainchainTxHash.decodeUnsafe("000000020067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F2"), 0),
        MainchainEpoch(192),
        MainchainBlockNumber(4),
        MainchainSlot(52574500),
        2
      ),
      committeeHash = Blake2bHash32.fromHexUnsafe("d5462cd23dcd12169df205f8bf659554441885fb39393a82a5e3b13601aa8cab"),
      sidechainEpoch = SidechainEpoch(1114)
    )

    val previousCommitteeNft: CommitteeNft = CommitteeNft(
      utxoInfo = UtxoInfo(
        UtxoId(MainchainTxHash.decodeUnsafe("000000010067F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F1"), 0),
        MainchainEpoch(191),
        MainchainBlockNumber(3),
        MainchainSlot(52142500),
        2
      ),
      committeeHash = Blake2bHash32.fromHexUnsafe("ffff2cd23dcd12169df205f8bf659554441885fb39393a82a5e3b13601aa8cab"),
      sidechainEpoch = SidechainEpoch(1113)
    )

    val merkleRootNft: MerkleRootNft = MerkleRootNft(
      merkleRootHash = Hex.decodeUnsafe("bdbd8d6191322e4e3a1788e1496d13fbdd9252e319e8e45372f65a068c5c3c2b"),
      txHash = MainchainTxHash.decodeUnsafe("000000010077F14D6F6436C7F7DEDB27CE3CEB4D2D18FF249D43B22D86FAE3F2"),
      blockNumber = MainchainBlockNumber(4),
      previousMerkleRootHash =
        Some(Hex.decodeUnsafe("bdbd8d6191322e4e3a1788e1496d13fbdd9252e319e8e45372f65a068c5c3c2a"))
    )
  }

  "should get registered candidates with their corresponding stake" in withMigrations(
    Migrations.EpochParams,
    Migrations.Blocks,
    Migrations.EpochPools,
    Migrations.EpochStake,
    Migrations.Transactions
  ) {
    val fixtures = new Fixtures(stabilityParameter = 2160L)
    import fixtures._
    val datasource        = DataSource.forDbSync[IO, ECDSA](fixtures.cardanoFollowerConfig, currentDb.xa)
    val resultFor190Epoch = datasource.getEpochData(MainchainEpoch(190))

    resultFor190Epoch.ioValue shouldMatchTo Some(epoch190Data)

    val resultFor191Epoch = datasource.getEpochData(MainchainEpoch(191))
    // There is no validator because:
    //   - the first validator deregistered
    //   - the second validator is not an spo anymore
    resultFor191Epoch.ioValue shouldMatchTo Some(EpochData(epoch190Nonce, List.empty))

    val resultForEpoch189 = datasource.getEpochData(MainchainEpoch(189))
    // Registrations were made after beginning of epoch 189, but anyway there is no stake distribution for 189.
    resultForEpoch189.ioValue shouldBe empty

  }

  "should get registered candidates, by slot, with their corresponding stake" in withMigrations(
    Migrations.EpochParams,
    Migrations.Blocks,
    Migrations.EpochPools,
    Migrations.EpochStake,
    Migrations.Transactions
  ) {
    val fixtures = new Fixtures(stabilityParameter = 2160L)
    import fixtures._
    val datasource = DataSource.forDbSync[IO, ECDSA](fixtures.cardanoFollowerConfig, currentDb.xa)

    //Given slot is in epoch 189, so stake distribution from epoch 188 is used, which is empty
    datasource.getMainchainData(MainchainSlot(51278420)).ioValue shouldMatchTo None
    //Given slot is in epoch 190, after block 0, before block 1
    datasource.getMainchainData(MainchainSlot(51710400)).ioValue shouldMatchTo Some(
      EpochData(epoch189Nonce, List(candidate1FromBlock0, candidate2FromBlock0))
    )
    //Given slot is in epoch 190, contains block 2
    datasource.getMainchainData(MainchainSlot(51710500)).ioValue shouldMatchTo Some(
      EpochData(epoch189Nonce, List(candidate1FromBlock0WithDeregistration, candidate2FromBlock0, candidate1FromBlock2))
    )
  }

  // scalastyle:off parameter.number
  private def candidateWithStake(
      mainchainPubKeyHex: String,
      sidechainPubKeyHex: String,
      mainchainSignatureHex: String,
      sidechainSignatureHex: String,
      stake: Lovelace,
      utxoIndex: Int,
      isPending: Boolean,
      mainchainTxHash: MainchainTxHash,
      mainchainEpoch: MainchainEpoch,
      mainchainBlockNumber: MainchainBlockNumber,
      mainchainSlot: MainchainSlot,
      txIndexWithinBlock: Int,
      consumedUtxo: UtxoId,
      txInputs: List[UtxoId],
      pendingRegistrationChange: Option[PendingRegistrationChange] = None
  ): LeaderCandidate[ECDSA] =
    LeaderCandidate(
      mainchainPubKey = EdDSA.PublicKey.fromHexUnsafe(mainchainPubKeyHex),
      NonEmptyList.one(
        RegistrationData[ECDSA](
          utxoInfo = UtxoInfo(
            UtxoId(mainchainTxHash, utxoIndex),
            mainchainEpoch,
            mainchainBlockNumber,
            mainchainSlot,
            txIndexWithinBlock
          ),
          consumedInput = consumedUtxo,
          txInputs = txInputs,
          sidechainPubKey = ECDSA.PublicKey.fromHex(sidechainPubKeyHex).value,
          crossChainPubKey = ECDSA.PublicKey.fromHex(sidechainPubKeyHex).value,
          sidechainSignature = ECDSASignatureNoRecovery.fromHexUnsafe(sidechainSignatureHex),
          crossChainSignature = ECDSASignatureNoRecovery.fromHexUnsafe(sidechainSignatureHex),
          stakeDelegation = stake,
          mainchainSignature =
            EdDSA.Signature.fromBytes(ByteString(Hex.decodeAsArrayUnsafe(mainchainSignatureHex))).value,
          isPending = isPending,
          pendingChange = pendingRegistrationChange
        )
      )
    )
  // scalastyle:on parameter.number

  "should return empty result when there is no data for given epoch" in withMigrations(
    Migrations.EpochParams,
    Migrations.EpochPools,
    Migrations.EpochStake
  ) {
    val fixtures   = new Fixtures(stabilityParameter = 2160L)
    val datasource = DataSource.forDbSync[IO, ECDSA](fixtures.cardanoFollowerConfig, currentDb.xa)
    val result     = datasource.getEpochData(MainchainEpoch(150))
    result.ioValue shouldBe empty
  }

  "should return no registered candidates when the stake distribution is not available for the current epoch" in withMigrations(
    Migrations.EpochParams,
    Migrations.EpochPools
  ) {
    val fixtures   = new Fixtures(stabilityParameter = 2160L)
    val datasource = DataSource.forDbSync[IO, ECDSA](fixtures.cardanoFollowerConfig, currentDb.xa)
    val result     = datasource.getEpochData(MainchainEpoch(191))
    result.ioValue shouldBe empty
  }

  "getNewTransactions returns unprocessed transactions" in withMigrations(Migrations.Blocks, Migrations.Transactions) {
    val fixtures   = new Fixtures(stabilityParameter = 0L)
    val datasource = DataSource.forDbSync[IO, ECDSA](fixtures.cardanoFollowerConfig, currentDb.xa)
    val result = datasource.getNewTransactions(
      after = Some(fixtures.tx1Hash),
      MainchainSlot(52142503)
    ) // the choice of the mainchain slot filters out 3rd tx that is not considered stable yet
    result.ioValue shouldMatchTo List(fixtures.xcTx1, fixtures.xcTx2)
  }

  "getNewTransactions filters out already processed transactions" in withMigrations(
    Migrations.Blocks,
    Migrations.Transactions
  ) {
    val fixtures   = new Fixtures(stabilityParameter = 0L)
    val datasource = DataSource.forDbSync[IO, ECDSA](fixtures.cardanoFollowerConfig, currentDb.xa)
    val result     = datasource.getNewTransactions(after = Some(fixtures.xcTxHash1), MainchainSlot(53006500))
    result.ioValue shouldMatchTo List(fixtures.xcTx2, fixtures.xcTx3)
  }

  "getNewTransactions filters out transactions from unstable blocks" in withMigrations(
    Migrations.Blocks,
    Migrations.Transactions
  ) {
    val fixtures   = new Fixtures(stabilityParameter = 1L)
    val datasource = DataSource.forDbSync[IO, ECDSA](fixtures.cardanoFollowerConfig, currentDb.xa)
    val result     = datasource.getNewTransactions(after = Some(fixtures.tx1Hash), MainchainSlot(52142500))
    result.ioValue shouldBe empty
  }

  "getNewTransactions filters out transactions with invalid redeemer datum" in withMigrations(
    Migrations.Blocks,
    Migrations.Transactions
  ) {
    val fixtures   = new Fixtures(stabilityParameter = 0L)
    val datasource = DataSource.forDbSync[IO, ECDSA](fixtures.cardanoFollowerConfig, currentDb.xa)
    val result     = datasource.getNewTransactions(after = Some(fixtures.xcTxHash1), MainchainSlot(52574500))
    result.ioValue shouldMatchTo List(fixtures.xcTx2, fixtures.xcTx3)
  }

  "getNewTransactions filters out already processed transactions from the same slot" in withMigrations(
    Migrations.Blocks,
    Migrations.Transactions
  ) {
    val fixtures   = new Fixtures(stabilityParameter = 0L)
    val datasource = DataSource.forDbSync[IO, ECDSA](fixtures.cardanoFollowerConfig, currentDb.xa)
    val result     = datasource.getNewTransactions(after = Some(fixtures.xcTxHash1), MainchainSlot(52142500))
    result.ioValue shouldMatchTo List(fixtures.xcTx2)
  }

  "get the latest checkpoint NFT" in withMigrations(Migrations.Blocks, Migrations.Transactions) {
    val fixtures   = new Fixtures(stabilityParameter = 0L)
    val datasource = DataSource.forDbSync[IO, ECDSA](fixtures.cardanoFollowerConfig, currentDb.xa)
    val result     = datasource.getLatestCheckpointNft().ioValue
    result shouldBe Some(fixtures.currentCheckpointNft)
  }

  "get the latest committee NFT" in withMigrations(Migrations.Blocks, Migrations.Transactions) {
    val fixtures   = new Fixtures(stabilityParameter = 0L)
    val datasource = DataSource.forDbSync[IO, ECDSA](fixtures.cardanoFollowerConfig, currentDb.xa)
    val result     = datasource.getLatestCommitteeNft().ioValue
    result shouldBe Some(fixtures.currentCommitteeNft)
  }

  "get latest merkle root nft" in withMigrations(Migrations.Blocks, Migrations.MerkleRoots) {
    val fixtures   = new Fixtures(stabilityParameter = 0L)
    val config     = fixtures.cardanoFollowerConfig.copy(merkleRootNftPolicyId = fixtures.referencedMerkleRootNftPolicy)
    val datasource = DataSource.forDbSync[IO, ECDSA](config, currentDb.xa)

    datasource.getLatestOnChainMerkleRootNft().ioValue shouldBe Some(fixtures.merkleRootNft)
  }
}
