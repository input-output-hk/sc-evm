package io.iohk.dataGenerator.services

import cats.effect.{IO, Resource}
import cats.implicits.showInterpolator
import io.iohk.scevm.consensus.validators.HeaderValidatorImpl
import io.iohk.scevm.db.storage.ObftStorageBuilder
import io.iohk.scevm.domain.{ObftBlock, ObftHeader}
import io.iohk.scevm.ledger.BlockImportService.UnvalidatedBlock
import io.iohk.scevm.ledger.{BlockImportService, BlockImportServiceImpl, LeaderElection, SlotDerivation}
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}

class PhaseImport private (
    importService: BlockImportService[IO]
)(implicit loggerFactory: LoggerFactory[IO]) {
  private val logger: SelfAwareStructuredLogger[IO] = loggerFactory.getLogger

  def run(blocks: fs2.Stream[IO, ObftBlock]): fs2.Stream[IO, BlockImportService.ImportedBlock] =
    blocks
      .evalTap(block => logger.debug(show"Importing block $block"))
      .evalTap(block => logger.trace(show"Importing block ${block.fullToString}"))
      .evalMap(block => importService.importAndValidateBlock(UnvalidatedBlock(block)))
      .evalMap {
        case Some(value) => IO.pure(value)
        case None        => IO.raiseError(new RuntimeException("The block was not imported"))
      }
}

object PhaseImport {
  def build(genesis: ObftHeader)(
      leaderElection: LeaderElection[IO],
      slotDerivation: SlotDerivation,
      obftStorageBuilder: ObftStorageBuilder[IO]
  )(implicit loggerFactory: LoggerFactory[IO]): Resource[IO, PhaseImport] =
    for {
      _              <- Resource.unit
      headerValidator = new HeaderValidatorImpl[IO](leaderElection, genesis, slotDerivation)
      importService = new BlockImportServiceImpl[IO](
                        obftStorageBuilder.blockchainStorage,
                        obftStorageBuilder.blockchainStorage,
                        headerValidator
                      )
    } yield new PhaseImport(importService)
}
