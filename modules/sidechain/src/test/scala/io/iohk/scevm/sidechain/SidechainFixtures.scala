package io.iohk.scevm.sidechain

import cats.Applicative
import cats.effect.IO
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignature}
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{Address, EpochPhase, Slot, UInt256}
import io.iohk.scevm.exec.vm.IOEvmCall
import io.iohk.scevm.sidechain.certificate.{CheckpointSigner, MerkleRootSigner}
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sidechain.transactions.{OutgoingTransaction, merkletree}
import io.iohk.scevm.testing.fixtures
import io.iohk.scevm.trustlesssidechain.SidechainParams
import io.iohk.scevm.trustlesssidechain.cardano.{MainchainEpoch, MainchainSlot, UtxoId}
import io.iohk.scevm.utils.SystemTime
import io.iohk.scevm.utils.SystemTime.{TimestampSeconds, UnixTimestamp}

import scala.concurrent.duration.DurationLong

object SidechainFixtures {

  val sidechainParams: SidechainParams = SidechainParams(
    ChainId(0x4e),
    fixtures.GenesisBlock.header.hash,
    UtxoId.parseUnsafe("7247647315d327d4e56fd3fe62d45be1dc3a76a647c910e0048cca8b97c8df3e#0"),
    2,
    3
  )

  final case class SidechainEpochDerivationStub[F[_]: Applicative](
      slotsInEpoch: Int = 12,
      genesisTime: UnixTimestamp = UnixTimestamp.fromSeconds(1_500_000_000),
      slotDurationSeconds: Long = 20,
      stabilityParameter: Int = 1,
      currentEpoch: SidechainEpoch = SidechainEpoch(1)
  ) extends SidechainEpochDerivation[F] {

    val numberOfSlotsInRegularPhase: Int = slotsInEpoch - (4 * stabilityParameter)
    require(numberOfSlotsInRegularPhase > 0, "not enough slots in epoch for given stability parameter")
    val numberOfSlotsInClosedPhase: Int = 2 * stabilityParameter

    override def getSidechainEpoch(slot: Slot): SidechainEpoch = SidechainEpoch((slot.number / slotsInEpoch).longValue)

    override def getSidechainEpoch(time: SystemTime.UnixTimestamp): Option[SidechainEpoch] = {
      val timeElapsed = time.millis - genesisTime.millis
      Option.when(timeElapsed >= 0)(SidechainEpoch(timeElapsed / sidechainEpochDurationMillis))
    }

    override def getSidechainEpochStartTime(epoch: SidechainEpoch): TimestampSeconds =
      genesisTime.add(slotDurationSeconds.seconds * slotsInEpoch * epoch.number).toTimestampSeconds

    override def getSidechainEpochPhase(slot: Slot): EpochPhase = {
      val slotInEpoch = slot.number % slotsInEpoch
      if (slotInEpoch < numberOfSlotsInRegularPhase) EpochPhase.Regular
      else if (slotInEpoch < numberOfSlotsInRegularPhase + numberOfSlotsInClosedPhase) EpochPhase.ClosedTransactionBatch
      else EpochPhase.Handover
    }

    private val sidechainEpochDurationMillis: Long = slotsInEpoch * slotDurationSeconds * 1000

    override def getCurrentEpoch: F[SidechainEpoch] = currentEpoch.pure[F]
  }

  final case class MainchainEpochDerivationStub(
      slotsInEpoch: Int = 12,
      sidechainEpochInMainchain: Int = 100,
      genesisTime: UnixTimestamp = UnixTimestamp.fromSeconds(1_500_000_000),
      mainchainGenesisTime: UnixTimestamp = UnixTimestamp.fromSeconds(1_000_000_000),
      slotDurationSeconds: Long = 20
  ) extends MainchainEpochDerivation {

    override def getMainchainEpochStartTime(epoch: MainchainEpoch): TimestampSeconds = {
      val mainchainEpochDuration = slotDurationSeconds.seconds * slotsInEpoch * sidechainEpochInMainchain
      mainchainGenesisTime
        .add(
          mainchainEpochDuration * epoch.number
        )
        .toTimestampSeconds
    }

    override def getMainchainEpoch(epoch: SidechainEpoch): MainchainEpoch = MainchainEpoch(
      epoch.number / sidechainEpochInMainchain
    )

    //Behaves like first mc epoch = 0 and first mc slot = 0 and mc slot is 1s
    override def getFirstMainchainSlot(sidechainEpoch: SidechainEpoch): MainchainSlot =
      MainchainSlot(sidechainEpoch.number * slotsInEpoch * slotDurationSeconds)

    override def getMainchainSlot(time: UnixTimestamp): MainchainSlot =
      MainchainSlot((time.millis - genesisTime.millis) / 1000)

  }

  def bridgeContractStub(
      outgoingTransactionList: Seq[OutgoingTransaction] = Nil,
      prevMerkleRoot: Option[RootHash] = None,
      stateRootHashToMerkleRoot: ByteString => Option[RootHash] = _ => None
  ): HandoverTransactionProvider.BridgeContract[IOEvmCall, ECDSA] =
    new HandoverTransactionProvider.BridgeContract[IOEvmCall, ECDSA] {
      override def createSignTransaction(
          handoverSignature: ECDSASignature,
          checkpoint: Option[CheckpointSigner.SignedCheckpoint],
          txsBatchMRH: Option[MerkleRootSigner.SignedMerkleRootHash[ECDSASignature]]
      ): IOEvmCall[SystemTransaction] = {
        val signTxPayload = BridgeContract.buildSignCallPayload(
          handoverSignature,
          checkpoint,
          txsBatchMRH
        )
        IOEvmCall.pure(
          SystemTransaction(
            chainId = ChainId(0),
            gasLimit = Long.MaxValue,
            receivingAddress = Address("0x696f686b2e6d616d626100000000000000000000"),
            value = 0,
            payload = signTxPayload
          )
        )
      }

      override def getTransactionsInBatch(batch: UInt256): IOEvmCall[Seq[OutgoingTransaction]] =
        IOEvmCall.pure(outgoingTransactionList)

      override def getPreviousMerkleRoot: IOEvmCall[Option[merkletree.RootHash]] =
        IOEvmCall.pure(prevMerkleRoot)

      override def getTransactionsMerkleRootHash(epoch: SidechainEpoch): IOEvmCall[Option[RootHash]] =
        IOEvmCall(s => IO.pure(stateRootHashToMerkleRoot(s.stateRootHash)))

    }
}
