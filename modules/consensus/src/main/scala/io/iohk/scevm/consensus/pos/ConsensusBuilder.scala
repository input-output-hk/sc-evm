package io.iohk.scevm.consensus.pos

import cats.Parallel
import cats.effect.std.Semaphore
import cats.effect.{Async, MonadCancelThrow}
import cats.syntax.all._
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.domain.{BranchService, BranchServiceImpl}
import io.iohk.scevm.consensus.metrics.ConsensusMetrics
import io.iohk.scevm.consensus.pos.ConsensusService.{BranchUpdateListener, ConsensusResult}
import io.iohk.scevm.consensus.validators._
import io.iohk.scevm.db.storage._
import io.iohk.scevm.ledger.BlockImportService.ImportedBlock
import org.typelevel.log4cats.LoggerFactory

trait ConsensusBuilder[F[_]] extends ConsensusService[F]

class ConsensusBuilderImpl[F[_]: Parallel: MonadCancelThrow: LoggerFactory] private (
    blockchainConfig: BlockchainConfig
)(
    blocksReader: BlocksReader[F],
    branchExecution: BranchExecution[F],
    branchProvider: BranchProvider[F],
    consensusSemaphore: Semaphore[F],
    currentBranchService: BranchUpdateListener[F],
    receiptStorage: ReceiptStorage[F],
    consensusMetrics: ConsensusMetrics[F]
) extends ConsensusBuilder[F] {
  private lazy val branchService: BranchService[F] = new BranchServiceImpl(blocksReader, branchProvider)(
    new ChainingValidatorImpl
  )
  private lazy val branchResolution: BranchResolution[F] =
    new BranchResolutionImpl[F](branchService, SimplestHeaderComparator)

  private lazy val consensusService =
    new ConsensusServiceImpl[F](
      branchService,
      branchResolution,
      branchExecution,
      receiptStorage,
      consensusSemaphore,
      currentBranchService,
      consensusMetrics
    )(blockchainConfig.stabilityParameter)

  override def resolve(importedBlock: ImportedBlock, currentBranch: CurrentBranch): F[ConsensusResult] =
    consensusService.resolve(importedBlock, currentBranch)
}

object ConsensusBuilderImpl {
  def build[F[_]: Parallel: Async: LoggerFactory](blockchainConfig: BlockchainConfig)(
      branchExecution: BranchExecution[F],
      branchUpdateListener: BranchUpdateListener[F],
      storageBuilder: ObftStorageBuilder[F],
      consensusMetrics: ConsensusMetrics[F]
  ): F[ConsensusBuilder[F]] = for {
    consensusSemaphore <- Semaphore[F](1)
  } yield new ConsensusBuilderImpl[F](blockchainConfig)(
    storageBuilder.blockchainStorage,
    branchExecution,
    storageBuilder.blockchainStorage,
    consensusSemaphore,
    branchUpdateListener,
    storageBuilder.receiptStorage,
    consensusMetrics
  )
}
