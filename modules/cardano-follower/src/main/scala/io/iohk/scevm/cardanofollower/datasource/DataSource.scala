package io.iohk.scevm.cardanofollower.datasource

import cats.arrow.FunctionK
import cats.data.{NonEmptyList, OptionT}
import cats.effect.{Async, Sync}
import cats.syntax.all._
import cats.{Applicative, Monad, MonadThrow, ~>}
import doobie.ConnectionIO
import doobie.util.transactor.Transactor
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.scevm.cardanofollower.CardanoFollowerConfig
import io.iohk.scevm.cardanofollower.datasource.dbsync.DbSyncRepository
import io.iohk.scevm.cardanofollower.plutus.TransactionRecipientDatum
import io.iohk.scevm.domain.{MainchainPublicKey, Token}
import io.iohk.scevm.plutus.DatumDecoder.DatumDecodeError
import io.iohk.scevm.plutus.{Datum, DatumDecoder}
import io.iohk.scevm.sidechain.{IncomingCrossChainTransaction, SidechainEpoch}
import io.iohk.scevm.trustlesssidechain._
import io.iohk.scevm.trustlesssidechain.cardano._
import org.typelevel.log4cats.{Logger, LoggerFactory}

import scala.reflect.runtime.universe.{TypeTag, typeOf}

import MainchainDataRepository.DbIncomingCrossChainTransaction

object DataSource {

  import org.typelevel.log4cats.slf4j.loggerFactoryforSync

  def forDbSync[F[_]: Async, CrossChainScheme <: AbstractSignatureScheme](
      mainchainConfig: CardanoFollowerConfig,
      xa: Transactor[F]
  ): DataSource[ConnectionIO, F, CrossChainScheme] = {
    val dbSyncExecute = new FunctionK[ConnectionIO, F] {
      override def apply[A](fa: ConnectionIO[A]): F[A] = xa.trans.apply(fa)
    }
    new DataSource[ConnectionIO, F, CrossChainScheme](mainchainConfig, DbSyncRepository, dbSyncExecute)
  }

}

/**  @param execute Compile an instance of Q[T] into a single instance of F[T] that executes atomically if Q has this capability
  */
// scalastyle:off number.of.methods
class DataSource[Q[_]: Sync: LoggerFactory, F[_]: Sync: LoggerFactory, CrossChainScheme <: AbstractSignatureScheme](
    mainchainConfig: CardanoFollowerConfig,
    mainchainDataRepository: MainchainDataRepository[Q],
    execute: Q ~> F
) extends MainchainDataSource[F, CrossChainScheme]
    with IncomingTransactionDataSource[F]
    with MainchainActiveFlowDataSource[F] {

  /* A cardano epoch lasts for 10 * stabilityParameter (often called security parameter in cardano, but equivalent to
   * our own stability parameter k). We want to look at stable transactions at the beginning of an epoch, so we take a
   * margin in term of slots to determine which slot will give us the state of registrations for the next epoch.
   * For now this formula makes it so that the block will approximately be around 8*k within its epoch,
   * so with a margin of 2k block on average.
   */
  private val slotMargin       = 2 * (mainchainConfig.stabilityParameter / mainchainConfig.activeSlotCoeff).longValue
  private val slotPerEpochs    = (mainchainConfig.epochDuration / mainchainConfig.slotDuration).longValue
  private val firstSlotNumber  = mainchainConfig.firstSlot.number
  private val firstEpochNumber = mainchainConfig.firstEpochNumber

  implicit private val logQ: Logger[Q]        = LoggerFactory[Q].getLogger
  implicit private val logF: Logger[F]        = LoggerFactory[F].getLogger
  private val transactionReceiverDatumDecoder = DatumDecoder[TransactionRecipientDatum]

  private val fuelAsset: Asset              = Asset(mainchainConfig.fuelMintingPolicyId, mainchainConfig.fuelAssetName)
  private val checkpointNftAsset: Asset     = Asset(mainchainConfig.checkpointNftPolicyId, AssetName.empty)
  private val committeeNftAsset: Asset      = Asset(mainchainConfig.committeeNftPolicyId, AssetName.empty)
  private val merkleRootNftPolicy: PolicyId = mainchainConfig.merkleRootNftPolicyId

  def getNewTransactions(
      after: Option[MainchainTxHash],
      at: MainchainSlot
  ): F[List[IncomingCrossChainTransaction]] =
    getTransactions(after, getStableBlockNumber(at))

  def getUnstableTransactions(
      after: Option[MainchainTxHash],
      at: MainchainSlot
  ): F[List[IncomingCrossChainTransaction]] =
    getTransactions(after, getBlockNumber(at))

  private def getTransactions(
      after: Option[MainchainTxHash],
      getBlockUpperBound: Q[MainchainBlockNumber]
  ): F[List[IncomingCrossChainTransaction]] =
    execute(for {
      block          <- getBlockUpperBound
      lowerSlot      <- getFromSlot(after)
      _              <- logQ.debug(s"Getting cross chain transactions after slot: $lowerSlot and up to block: $block")
      dbTransactions <- mainchainDataRepository.getCrossChainTransactions(lowerSlot, block, fuelAsset)
    } yield dbTransactions).flatMap { dbTransactions =>
      val indexOfAfterTx    = after.map(tx => dbTransactions.indexWhere(_.txHash == tx)).getOrElse(-1)
      val transactionsAfter = dbTransactions.drop(indexOfAfterTx + 1)
      transactionsAfter.toList
        .traverse(decodedIncomingCrossChainTransactionDatum)
        .map(_.flatten)
        .flatTap(txs => logF.debug(show"Cross chain transactions ids: ${txs.map(_.txId)} "))
    }

  private def getFromSlot(since: Option[MainchainTxHash]): Q[MainchainSlot] =
    since match {
      case Some(txHash) =>
        mainchainDataRepository.getSlotForTxHash(txHash).flatMap {
          case Some(slot) => Monad[Q].pure(slot)
          case None =>
            val msg =
              show"""Couldn't find main chain block for txHash $txHash in the main chain datasource.
                    |A potential cause is a datasource not fully up-to-date with the main chain""".stripMargin
            logQ.error(msg) >> MonadThrow[Q].raiseError[MainchainSlot](new Exception(msg))
        }
      case None => Monad[Q].pure(mainchainConfig.firstSlot)
    }

  private def getStableBlockNumber(currentSlot: MainchainSlot): Q[MainchainBlockNumber] =
    getBlockNumber(currentSlot).map(bn => MainchainBlockNumber(bn.value - mainchainConfig.stabilityParameter))

  private def getBlockNumber(currentSlot: MainchainSlot): Q[MainchainBlockNumber] =
    mainchainDataRepository
      .getLatestBlockForSlot(currentSlot)
      .map(blockOpt => MainchainBlockNumber(blockOpt.map(_.number.value).getOrElse(0L)))

  private def decodedIncomingCrossChainTransactionDatum(
      incomingTx: DbIncomingCrossChainTransaction
  ): F[Option[IncomingCrossChainTransaction]] =
    transactionReceiverDatumDecoder
      .decode(incomingTx.datum)
      .fold(
        err =>
          logF
            .error(
              show"""Invalid incoming transaction '$incomingTx', $err. This can be due to:
                | - This version of EVM sidechain being not compatible with the main chain token minting policy (outdated datum parser)
                | - A bug in the minting policy allowing someone to burn tokens without providing a recipient""".stripMargin
            )
            .as(none),
        datum =>
          IncomingCrossChainTransaction(
            datum,
            Token(-incomingTx.value.value),
            incomingTx.txHash,
            MainchainBlockNumber(incomingTx.blockNumber.value + mainchainConfig.stabilityParameter)
          ).some.pure[F]
      )

  override def getEpochData(epoch: MainchainEpoch): F[Option[EpochData[CrossChainScheme]]] =
    getMainchainData(getFirstSlotOfEpoch(epoch))

  private def mainchainEpochForSlot(slot: MainchainSlot): MainchainEpoch =
    MainchainEpoch(firstEpochNumber + (slot.number - firstSlotNumber) / slotPerEpochs)

  override def getMainchainData(slot: MainchainSlot): F[Option[EpochData[CrossChainScheme]]] = {
    val epoch = mainchainEpochForSlot(slot)
    execute((for {
      previousEpochNonce <- OptionT(getEpochNonce(epoch.prev))
      registrationData   <- getRegisteredCandidates(epoch, slot)
      candidates = registrationData
                     .groupMap(_._1)(_._2)
                     .map { case (pubKey, registrations) =>
                       LeaderCandidate(pubKey, NonEmptyList.fromListUnsafe(registrations.toList))
                     }
                     .toList
    } yield EpochData(previousEpochNonce, candidates)).value)
  }

  private def getRegisteredCandidates(epoch: MainchainEpoch, slot: MainchainSlot) =
    getCandidatesWithStakeDistribution(epoch, slot)
      .semiflatMap { case (candidateList, poolHashToStakeDelegation) =>
        candidateList
          .traverseFilter { candidate =>
            matchWithStakeDelegation(candidate, poolHashToStakeDelegation)
          }
      }

  private def getCandidatesWithStakeDistribution(epoch: MainchainEpoch, slot: MainchainSlot) =
    for {
      rcs <- OptionT(getRegisteredCandidatesFromDb(epoch, slot))
      // We take the stake distribution from the previous epoch due to db-sync limitations
      sds <- OptionT.liftF(mainchainDataRepository.getStakeDistribution(epoch.prev)).filter(_.nonEmpty)
    } yield (rcs, sds)

  private def getRegisteredCandidatesFromDb(
      epoch: MainchainEpoch,
      maxSlot: MainchainSlot
  ): Q[Option[Vector[RegisteredCandidate[CrossChainScheme]]]] =
    for {
      _                     <- logQ.debug(show"Looking for registration state at slot $maxSlot")
      latestBlockForMaxSlot <- mainchainDataRepository.getLatestBlockForSlot(maxSlot)
      candidates <- latestBlockForMaxSlot match {
                      case Some(maxBlock) => getRegisteredCandidates(epoch, maxBlock).map(Some(_))
                      case None           => logQ.debug(show"No block found for slot: $maxSlot").as(Option.empty)
                    }
    } yield candidates

  private def getRegisteredCandidates(
      epoch: MainchainEpoch,
      maxBlock: MainchainBlockInfo
  ): Q[Vector[RegisteredCandidate[CrossChainScheme]]] =
    for {
      block <- getBlockForRegistrations(epoch)

      nonPendingUtxos  <- mainchainDataRepository.getUtxosForAddress(mainchainConfig.committeeCandidateAddress, block)
      activeCandidates <- utxosToCandidates(upToSlot = maxBlock.slot, utxos = nonPendingUtxos, isPending = false)

      pendingUtxos <-
        mainchainDataRepository.getUtxosForAddress(
          mainchainConfig.committeeCandidateAddress,
          maxBlock.number,
          Some(block)
        )
      pendingCandidates <- utxosToCandidates(upToSlot = maxBlock.slot, utxos = pendingUtxos, isPending = true)

    } yield activeCandidates ++ pendingCandidates

  private def getBlockForRegistrations(epoch: MainchainEpoch): Q[MainchainBlockNumber] = {
    val stableSlot = getStableSlotAtBeginningOfEpoch(epoch)
    mainchainDataRepository.getLatestBlockForSlot(stableSlot).map {
      case Some(block) => block.number
      case None        => MainchainBlockNumber(-1)
    }
  }

  private def getStableSlotAtBeginningOfEpoch(epoch: MainchainEpoch): MainchainSlot =
    MainchainSlot(getFirstSlotOfEpoch(epoch).number - slotMargin)

  private def utxosToCandidates(
      upToSlot: MainchainSlot,
      utxos: Vector[MainchainTxOutput],
      isPending: Boolean
  ): Q[Vector[RegisteredCandidate[CrossChainScheme]]] =
    for {
      parsed <- parseCandidates(utxos)
    } yield parsed.map { case ParsedCandidate(txInfo, datum, spentBy, txInputs) =>
      RegisteredCandidate(
        mainchainPubKey = datum.mainchainPubKey,
        sidechainPubKey = datum.sidechainPubKey,
        crossChainPubKey = datum.crossChainPubKey,
        sidechainSignature = datum.sidechainSignature,
        mainchainSignature = datum.mainchainSignature,
        crossChainSignature = datum.crossChainSignature,
        consumedInput = datum.consumedInput,
        txInputs = txInputs,
        utxoInfo = txInfo,
        isPending = isPending,
        // We hide the deregistration if it happened after maxBlock to return the same value even if
        // the validator deregistered in the meantime
        pendingChange = spentBy
          .filter(_.slot.number <= upToSlot.number)
          .map(spentByToPendingDeregistration)
          .orElse(Option.when(isPending)(txInfoToPendingRegistration(txInfo)))
      )
    }

  private def activeFrom(epoch: MainchainEpoch, txSlot: MainchainSlot) = {
    val stableSlot = getStableSlotAtBeginningOfEpoch(epoch.next)
    if (txSlot.number <= stableSlot.number)
      epoch.next
    else
      epoch.next.next
  }

  private def spentByToPendingDeregistration(spentByInfo: SpentByInfo) =
    PendingDeregistration(
      spentByInfo.transactionHash,
      activeFrom(spentByInfo.epoch, spentByInfo.slot)
    )

  private def txInfoToPendingRegistration(txInfo: UtxoInfo) =
    PendingRegistration(
      txInfo.utxoId.txHash,
      activeFrom(txInfo.epoch, txInfo.slotNumber)
    )

  private def getFirstSlotOfEpoch(epoch: MainchainEpoch): MainchainSlot = {
    val slotSinceFirstEpoch = (epoch.number - mainchainConfig.firstEpochNumber) * slotPerEpochs
    MainchainSlot((slotSinceFirstEpoch + mainchainConfig.firstSlot.number).longValue)
  }

  private def parseCandidates(
      utxos: Vector[MainchainTxOutput]
  ): Q[Vector[ParsedCandidate[CrossChainScheme]]] = {
    val decoder = DatumDecoder[RegisterValidatorDatum[CrossChainScheme]]
    val (errors, registrations) = utxos.partitionMap { utxo =>
      def txInfo = UtxoInfo(
        utxoId = utxo.id,
        epoch = utxo.epochNumber,
        blockNumber = utxo.blockNumber,
        slotNumber = utxo.slotNumber,
        txIndexWithinBlock = utxo.txIndexInBlock
      )
      utxo.datum
        .toRight(DatumDecodeError("No datum for utxo"))
        .flatMap(decoder.decode)
        .map(ParsedCandidate(txInfo, _, utxo.spentBy, utxo.txInputs))
        .left
        .map(utxo -> _)
    }

    val logErrors = if (errors.nonEmpty) {
      logQ.info(
        "Founds some utxos on the main chain registration contract that cannot be parsed: \n"
          + errors.map { case (utxo, e) => show" - ${utxo.id}: ${e.message}" }.mkString("\n")
      )
    } else {
      Monad[Q].unit
    }

    logErrors.as(registrations)
  }

  private def matchWithStakeDelegation(
      candidate: RegisteredCandidate[CrossChainScheme],
      poolHashToStakeDelegation: Map[Blake2bHash28, Lovelace]
  ): Q[Option[(MainchainPublicKey, RegistrationData[CrossChainScheme])]] = {
    val poolHash = Blake2bHash28.hash(candidate.mainchainPubKey.bytes)
    poolHashToStakeDelegation.get(poolHash) match {
      case Some(delegatedAmount) =>
        Applicative[Q].pure(
          Some(
            candidate.mainchainPubKey ->
              RegistrationData(
                candidate.consumedInput,
                candidate.txInputs,
                candidate.sidechainSignature,
                delegatedAmount,
                candidate.mainchainSignature,
                candidate.crossChainSignature,
                candidate.sidechainPubKey,
                candidate.crossChainPubKey,
                candidate.utxoInfo,
                candidate.isPending,
                candidate.pendingChange
              )
          )
        )
      case None =>
        logQ.debug(show"Ignoring registered candidate $candidate because it has no stake").as(None)
    }
  }

  private def getEpochNonce(epoch: MainchainEpoch): Q[Option[EpochNonce]] =
    mainchainDataRepository.getEpochNonce(epoch)

  def getLatestBlockInfo: F[Option[MainchainBlockInfo]] =
    execute(mainchainDataRepository.getLatestBlockInfo)

  def getStableBlockInfo(currentSlot: MainchainSlot): F[Option[MainchainBlockInfo]] =
    execute(getStableBlockNumber(currentSlot) >>= mainchainDataRepository.getBlockInfoForNumber)

  override def getLatestCheckpointNft(): F[Option[CheckpointNft]] =
    execute(
      OptionT(mainchainDataRepository.getNftUtxo(checkpointNftAsset))
        .semiflatMap[CheckpointNft] {
          case nft @ NftTxOutput(_, _, _, _, _, None) =>
            Sync[Q]
              .raiseError(missingDatumException(checkpointNftAsset, nft))
          case NftTxOutput(id, epoch, blockNumber, slot, txIndexWithinBlock, Some(datum)) =>
            Sync[Q]
              .fromEither(decodeDatum[CheckpointDatum](datum))
              .map(datum =>
                CheckpointNft(
                  UtxoInfo(id, epoch, blockNumber, slot, txIndexWithinBlock),
                  datum.sidechainBlockHash,
                  datum.sidechainBlockNumber
                )
              )
        }
        .value
    )

  override def getLatestCommitteeNft(): F[Option[CommitteeNft]] =
    execute(
      OptionT(mainchainDataRepository.getNftUtxo(committeeNftAsset))
        .semiflatMap[CommitteeNft] {
          case nft @ NftTxOutput(_, _, _, _, _, None) =>
            Sync[Q]
              .raiseError(missingDatumException(committeeNftAsset, nft))
          case NftTxOutput(id, epoch, blockNumber, slot, txIndexWithinBlock, Some(datum)) =>
            Sync[Q]
              .fromEither(decodeDatum[CommitteeDatum](datum))
              .map(datum =>
                CommitteeNft(
                  UtxoInfo(id, epoch, blockNumber, slot, txIndexWithinBlock),
                  datum.committeePubKeysHash,
                  SidechainEpoch(datum.sidechainEpoch)
                )
              )
        }
        .value
    )

  override def getLatestOnChainMerkleRootNft(): F[Option[MerkleRootNft]] =
    execute(OptionT(mainchainDataRepository.getLatestMintAction(merkleRootNftPolicy)).semiflatMap {
      case mint @ MintAction(_, _, _, _, None) =>
        Sync[Q]
          .raiseError[MerkleRootNft](
            new RuntimeException(show"""Technical error: Missing redeemer for saved merkle root NFT $mint.
                    |This means that there is a bug in the plutus contract, or that this node is outdated.
                    |""".stripMargin)
          )
      case MintAction(txHash, assetName, blockNumber, _, Some(redeemer)) =>
        DatumDecoder[SignedMerkleRootRedeemer].decode(redeemer) match {
          case Left(error) =>
            val message =
              show"""Could not decode the signed merkle root redeemer from main chain. This can be due to:
                    | - This version of EVM sidechain being not compatible with the main chain token minting policy (outdated datum parser)
                    | - A bug in the Cardano merkle root minting policy allowing the creation of an invalid datum
                    |Error: ${error.message}
                    |Datum: ${redeemer}""".stripMargin
            logQ.error(message) >>
              Sync[Q].raiseError[MerkleRootNft](new RuntimeException(message))
          case Right(signedMerkleRootHash) =>
            logQ
              .debug(
                show"Found saved merkle root hash $assetName, at txHash: $txHash, at block: $blockNumber with following redeemer: $signedMerkleRootHash"
              )
              .as(
                MerkleRootNft(
                  merkleRootHash = assetName.value,
                  txHash,
                  blockNumber,
                  signedMerkleRootHash.previousMerkleRoot
                )
              )
        }
    }.value)

  private def decodeDatum[T: DatumDecoder: TypeTag](
      datum: Datum
  ): Either[Exception, T] =
    DatumDecoder[T]
      .decode(datum)
      .leftMap(error =>
        new RuntimeException(
          show"""Technical error: could not decode ${typeOf[T].toString} datum.
                |This means that there is a bug in the Plutus contract, or that our datum parser is outdated.
                |Error: ${error.message}
                |Datum: $datum""".stripMargin
        )
      )

  private def missingDatumException(asset: Asset, nft: NftTxOutput) =
    new RuntimeException(
      show"""Technical error: Missing datum for saved ${asset.name} NFT: $nft.
            |This means that there is a bug in the Plutus contract, or that this node is outdated.
            |""".stripMargin
    )
}
// scalastyle:on number.of.methods

final case class ParsedCandidate[CrossChainScheme <: AbstractSignatureScheme](
    utxoInfo: UtxoInfo,
    datum: RegisterValidatorDatum[CrossChainScheme],
    spendBy: Option[SpentByInfo],
    txInputs: List[UtxoId]
)
