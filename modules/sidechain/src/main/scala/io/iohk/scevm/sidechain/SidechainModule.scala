package io.iohk.scevm.sidechain

import cats.effect.{Async, MonadCancelThrow}
import cats.syntax.all._
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.scevm.cardanofollower.datasource.{EpochData, LeaderCandidate, MainchainDataSource}
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionResult
import io.iohk.scevm.consensus.pos.{BlockPreExecution, ObftBlockExecution}
import io.iohk.scevm.consensus.validators.PostExecutionValidator
import io.iohk.scevm.consensus.validators.PostExecutionValidator.PostExecutionError
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.mempool.SignedTransactionMempool
import io.iohk.scevm.exec.vm.{EvmCall, PR, ProgramResultError, TransactionSimulator, WorldType}
import io.iohk.scevm.ledger.LeaderElection
import io.iohk.scevm.sidechain.CrossChainSignaturesService.CrossChainSignatures
import io.iohk.scevm.sidechain.MainchainStatusProvider.MainchainStatus
import io.iohk.scevm.sidechain.certificate.MissingCertificatesResolver
import io.iohk.scevm.sidechain.committee.{
  CandidateRegistration,
  CandidateRegistrationValidator,
  SidechainCommitteeProvider
}
import io.iohk.scevm.sidechain.transactions.MerkleProofService.SerializedMerkleProofWithDetails
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sidechain.transactions.{
  MerkleProofService,
  OutgoingTransaction,
  OutgoingTransactionService,
  OutgoingTxId
}
import io.iohk.scevm.sync.NextBlockTransactions
import io.iohk.scevm.trustlesssidechain.cardano.{MainchainBlockInfo, MainchainEpoch, MainchainSlot}
import io.iohk.scevm.utils.SystemTime.{TimestampSeconds, UnixTimestamp}
import io.janstenpickle.trace4cats.inject.Trace

trait SidechainModule[F[_], CrossChainScheme <: AbstractSignatureScheme]
    extends ChainModule[F]
    with SidechainCommitteeProvider[F, CrossChainScheme]
    with MainchainStatusProvider[F]
    with PendingIncomingCrossChainTransactionProvider[F]
    with MainchainDataSource[F, CrossChainScheme] {

  def getSidechainEpoch(slot: Slot): SidechainEpoch

  def getSidechainEpoch(timestamp: UnixTimestamp): Option[SidechainEpoch]

  def getCurrentEpoch: F[SidechainEpoch]

  def getFirstMainchainSlot(sidechainEpoch: SidechainEpoch): MainchainSlot

  def getMainchainSlot(ts: UnixTimestamp): MainchainSlot

  def getMainchainEpoch(sidechainEpoch: SidechainEpoch): MainchainEpoch

  def getSidechainEpochPhase(slot: Slot): EpochPhase

  def getMainchainEpochStartTime(epoch: MainchainEpoch): TimestampSeconds

  def getSidechainEpochStartTime(epoch: SidechainEpoch): TimestampSeconds

  def validateCandidate(
      leaderCandidate: LeaderCandidate[CrossChainScheme]
  ): F[CandidateRegistration[CrossChainScheme]]

  def getSignatures(epoch: SidechainEpoch): F[Either[String, CrossChainSignatures]]

  def getOutgoingTransaction(epoch: SidechainEpoch): F[Seq[OutgoingTransaction]]

  def getMerkleProof(epoch: SidechainEpoch, txId: OutgoingTxId): F[Option[SerializedMerkleProofWithDetails]]

  def getMissingCertificates(limit: Int): F[Either[String, List[(SidechainEpoch, List[RootHash])]]]
}

private class SidechainModuleImpl[F[_]: MonadCancelThrow, SignatureScheme <: AbstractSignatureScheme](
    leaderElection: LeaderElection[F],
    committeeProvider: SidechainCommitteeProvider[F, SignatureScheme],
    pendingTransactionsProvider: PendingIncomingCrossChainTransactionProvider[F],
    nextTransactionsProvider: NextBlockTransactions[F],
    sidechainEpochDerivation: SidechainEpochDerivation[F],
    mainchainEpochDerivation: MainchainEpochDerivation,
    mainchainDataSource: MainchainDataSource[F, SignatureScheme],
    candidateValidator: CandidateRegistrationValidator[F, SignatureScheme],
    signaturesService: CrossChainSignaturesService[F, SignatureScheme],
    transactionSimulator: TransactionSimulator[EvmCall[F, *]],
    outgoingTransactionService: OutgoingTransactionService[F],
    postExecutionValidator: PostExecutionValidator[F],
    preExecution: BlockPreExecution[F],
    merkleProofService: MerkleProofService[F],
    missingCertificatesResolver: MissingCertificatesResolver[F],
    override val ticker: fs2.Stream[F, Tick]
) extends SidechainModule[F, SignatureScheme] {

  override def getSlotLeader(slot: Slot): F[Either[LeaderElection.ElectionFailure, SidechainPublicKey]] =
    leaderElection.getSlotLeader(slot)

  override def getCommittee(
      epoch: SidechainEpoch
  ): F[Either[LeaderElection.ElectionFailure, SidechainCommitteeProvider.CommitteeElectionSuccess[SignatureScheme]]] =
    committeeProvider.getCommittee(epoch)

  override def getStatus: F[MainchainStatus] =
    for {
      latestBlock <- mainchainDataSource.getLatestBlockInfo
      stableBlock <- latestBlock.map(_.slot).flatTraverse(mainchainDataSource.getStableBlockInfo)
    } yield MainchainStatus(latestBlock, stableBlock)

  override def getNextTransactions(
      slot: Slot,
      keySet: LeaderSlotEvent.KeySet,
      best: ObftHeader
  ): F[Either[String, List[SignedTransaction]]] =
    nextTransactionsProvider.getNextTransactions(slot, keySet, best)

  override def getPendingTransactions(
      world: WorldType
  ): F[PendingTransactionsService.PendingTransactionsResponse] =
    pendingTransactionsProvider.getPendingTransactions(world)

  override def getSidechainEpoch(slot: Slot): SidechainEpoch =
    sidechainEpochDerivation.getSidechainEpoch(slot)

  override def getSidechainEpoch(timestamp: UnixTimestamp): Option[SidechainEpoch] =
    sidechainEpochDerivation.getSidechainEpoch(timestamp)

  override def getFirstMainchainSlot(sidechainEpoch: SidechainEpoch): MainchainSlot =
    mainchainEpochDerivation.getFirstMainchainSlot(sidechainEpoch)

  override def getMainchainSlot(ts: UnixTimestamp): MainchainSlot =
    mainchainEpochDerivation.getMainchainSlot(ts)

  override def getMainchainEpoch(sidechainEpoch: SidechainEpoch): MainchainEpoch =
    mainchainEpochDerivation.getMainchainEpoch(sidechainEpoch)

  override def getCurrentEpoch: F[SidechainEpoch] =
    sidechainEpochDerivation.getCurrentEpoch

  override def getMainchainEpochStartTime(epoch: MainchainEpoch): TimestampSeconds =
    mainchainEpochDerivation.getMainchainEpochStartTime(epoch)

  override def getSidechainEpochStartTime(epoch: SidechainEpoch): TimestampSeconds =
    sidechainEpochDerivation.getSidechainEpochStartTime(epoch)

  def getSidechainEpochPhase(slot: Slot): EpochPhase =
    sidechainEpochDerivation.getSidechainEpochPhase(slot)

  override def getEpochData(epoch: MainchainEpoch): F[Option[EpochData[SignatureScheme]]] =
    mainchainDataSource.getEpochData(epoch)

  override def getMainchainData(slot: MainchainSlot): F[Option[EpochData[SignatureScheme]]] =
    mainchainDataSource.getMainchainData(slot)

  override def getLatestBlockInfo: F[Option[MainchainBlockInfo]] = mainchainDataSource.getLatestBlockInfo

  override def getStableBlockInfo(currentSlot: MainchainSlot): F[Option[MainchainBlockInfo]] =
    mainchainDataSource.getStableBlockInfo(currentSlot)
  override def validateCandidate(
      leaderCandidate: LeaderCandidate[SignatureScheme]
  ): F[CandidateRegistration[SignatureScheme]] = candidateValidator.validateCandidate(leaderCandidate).pure[F]

  override def getSignatures(epoch: SidechainEpoch): F[Either[String, CrossChainSignatures]] =
    signaturesService.getSignatures(epoch).map(_.widen)

  override def getOutgoingTransaction(epoch: SidechainEpoch): F[Seq[OutgoingTransaction]] =
    outgoingTransactionService.getOutgoingTransaction(epoch)

  override def estimateGas(
      transaction: Transaction,
      senderAddress: Address = Address(0),
      blockContextMod: BlockContext => BlockContext = identity
  ): EvmCall[F, Either[ProgramResultError, BigInt]] =
    transactionSimulator.estimateGas(transaction, senderAddress, blockContextMod)

  override def executeTx(
      transaction: Transaction,
      senderAddress: Address = Address(0),
      blockContextMod: BlockContext => BlockContext = identity
  ): EvmCall[F, PR] = transactionSimulator.executeTx(transaction, senderAddress, blockContextMod)

  override def validate(
      initialState: WorldType,
      header: ObftHeader,
      result: ObftBlockExecution.BlockExecutionResult
  ): F[Either[PostExecutionValidator.PostExecutionError, Unit]] =
    postExecutionValidator.validate(initialState, header, result)

  override def prepare(state: WorldType): F[WorldType] =
    preExecution.prepare(state)

  override def getMerkleProof(epoch: SidechainEpoch, txId: OutgoingTxId): F[Option[SerializedMerkleProofWithDetails]] =
    merkleProofService.getProof(epoch, txId)

  override def getMissingCertificates(limit: Int): F[Either[String, List[(SidechainEpoch, List[RootHash])]]] =
    missingCertificatesResolver.getMissingCertificates(limit)

}

private class StandaloneMode[F[_]: MonadCancelThrow](
    leaderElection: LeaderElection[F],
    mempool: SignedTransactionMempool[F],
    postExecutionValidator: PostExecutionValidator[F],
    preExecution: BlockPreExecution[F],
    transactionSimulator: TransactionSimulator[EvmCall[F, *]],
    override val ticker: fs2.Stream[F, Tick]
) extends ChainModule[F] {

  override def getSlotLeader(slot: Slot): F[Either[LeaderElection.ElectionFailure, SidechainPublicKey]] =
    leaderElection.getSlotLeader(slot)

  override def getNextTransactions(
      slot: Slot,
      keySet: LeaderSlotEvent.KeySet,
      best: ObftHeader
  ): F[Either[String, List[SignedTransaction]]] =
    mempool.getAliveElements(slot).map(Right(_))

  override def estimateGas(
      transaction: Transaction,
      senderAddress: Address = Address(0),
      blockContextMod: BlockContext => BlockContext = identity
  ): EvmCall[F, Either[ProgramResultError, BigInt]] =
    transactionSimulator.estimateGas(transaction, senderAddress, blockContextMod)

  override def executeTx(
      transaction: Transaction,
      senderAddress: Address = Address(0),
      blockContextMod: BlockContext => BlockContext = identity
  ): EvmCall[F, PR] = transactionSimulator.executeTx(transaction, senderAddress, blockContextMod)

  override def prepare(state: WorldType): F[WorldType] = preExecution.prepare(state)

  override def validate(
      initialState: WorldType,
      header: ObftHeader,
      result: BlockExecutionResult
  ): F[Either[PostExecutionError, Unit]] =
    postExecutionValidator.validate(initialState, header, result)

}

object SidechainModule {
  // scalastyle:off parameter.number
  def withSidechain[
      F[_]: Async: Trace,
      SignatureScheme <: AbstractSignatureScheme
  ](
      committeeProvider: SidechainCommitteeProvider[F, SignatureScheme],
      pendingTransactionsProvider: PendingIncomingCrossChainTransactionProvider[F],
      nextTransactionsProvider: NextBlockTransactions[F],
      sidechainEpochDerivation: SidechainEpochDerivation[F],
      mainchainEpochDerivation: MainchainEpochDerivation,
      mainchainDataSource: MainchainDataSource[F, SignatureScheme],
      candidateValidator: CandidateRegistrationValidator[F, SignatureScheme],
      crossChainSignaturesService: CrossChainSignaturesService[F, SignatureScheme],
      transactionSimulator: TransactionSimulator[EvmCall[F, *]],
      outgoingTransactionService: OutgoingTransactionService[F],
      postExecutionValidator: PostExecutionValidator[F],
      preExecution: BlockPreExecution[F],
      merkleProofService: MerkleProofService[F],
      missingCertificatesResolver: MissingCertificatesResolver[F],
      ticker: fs2.Stream[F, Tick]
  ): SidechainModule[F, SignatureScheme] = {
    val leaderElection = new SidechainLeaderElection[F, SignatureScheme](sidechainEpochDerivation, committeeProvider)
    new SidechainModuleImpl[F, SignatureScheme](
      leaderElection,
      committeeProvider,
      pendingTransactionsProvider,
      nextTransactionsProvider,
      sidechainEpochDerivation,
      mainchainEpochDerivation,
      mainchainDataSource,
      candidateValidator,
      crossChainSignaturesService,
      transactionSimulator,
      outgoingTransactionService,
      postExecutionValidator,
      preExecution,
      merkleProofService,
      missingCertificatesResolver,
      ticker
    )
  }
  // scalastyle:on parameter.number

  def standalone[F[_]: MonadCancelThrow](
      leaderElection: LeaderElection[F],
      mempool: SignedTransactionMempool[F],
      postExecutionValidator: PostExecutionValidator[F],
      preExecution: BlockPreExecution[F],
      transactionSimulator: TransactionSimulator[EvmCall[F, *]],
      ticker: fs2.Stream[F, Tick]
  ): ChainModule[F] =
    new StandaloneMode[F](leaderElection, mempool, postExecutionValidator, preExecution, transactionSimulator, ticker)
}
