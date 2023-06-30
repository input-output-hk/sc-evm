package io.iohk.scevm.ledger

import cats.Monad
import cats.data.OptionT
import cats.syntax.all._
import io.iohk.scevm.consensus.validators.HeaderValidator
import io.iohk.scevm.consensus.validators.HeaderValidator.HeaderError
import io.iohk.scevm.db.storage.{BlocksReader, BlocksWriter}
import io.iohk.scevm.domain.{BlockHash, ObftBlock, ObftHeader}
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}

import BlockImportService.{BlockWithValidatedHeader, ImportedBlock, PreValidatedBlock, UnvalidatedBlock, ValidatedBlock}

trait BlockImportService[F[_]] {
  def importBlock(preValidatedBlock: PreValidatedBlock): F[Option[ImportedBlock]]
  def importAndValidateBlock(unvalidatedBlock: UnvalidatedBlock): F[Option[ImportedBlock]]
}

object BlockImportService {
  def apply[F[_]](implicit ev: BlockImportService[F]): BlockImportService[F] = ev

  sealed trait FreshBlock {
    val block: ObftBlock
    val idTag: String      = block.header.idTag
    val header: ObftHeader = block.header
    val hash: BlockHash    = block.header.hash
  }

  // Block received from peer, no validation has been performed yet
  final case class UnvalidatedBlock(block: ObftBlock) extends FreshBlock {
    def validate[F[_]: LoggerFactory: Monad](
        headerValidator: HeaderValidator[F]
    ): F[Option[BlockWithValidatedHeader]] = {
      val log: SelfAwareStructuredLogger[F] = LoggerFactory.getLogger[F]

      for {
        _ <- log.debug(s"About to check if header of block $idTag needs to be validated still")
        result <- headerValidator.validate(header).flatMap {
                    case Left(error) =>
                      log
                        .warn(
                          s"Validation of block $idTag failed. Reason: ${HeaderError.userFriendlyMessage(error)}"
                        ) >>
                        none[ObftBlock].pure
                    case Right(_) =>
                      log.debug(s"Validation of block $idTag passed.") >>
                        block.some.pure
                  }
      } yield result.map(BlockWithValidatedHeader)
    }
  }

  sealed trait PreValidatedBlock extends FreshBlock

  // Block that is already pre-validated (only the header has been validated)
  final case class BlockWithValidatedHeader(block: ObftBlock) extends PreValidatedBlock

  // Block that is validated (valid header and it makes a valid chain)
  final case class ValidatedBlock(block: ObftBlock) extends PreValidatedBlock

  // Block that is already pre-validated (only the header has been validated)
  final case class ImportedBlock(block: ObftBlock, fullyValidated: Boolean) extends FreshBlock
}

class BlockImportServiceImpl[F[_]: LoggerFactory: Monad](
    blocksReader: BlocksReader[F],
    blocksWriter: BlocksWriter[F],
    headerValidator: HeaderValidator[F]
) extends BlockImportService[F] {

  private val log: SelfAwareStructuredLogger[F] = LoggerFactory[F].getLogger

  override def importBlock(preValidatedBlock: PreValidatedBlock): F[Option[ImportedBlock]] =
    blocksReader.getBlock(preValidatedBlock.hash).flatMap {
      case Some(block) =>
        log.trace(show"Already imported $block") >>
          none[ImportedBlock].pure
      case None =>
        (for {
          _ <- OptionT.liftF(log.debug(show"About to import block ${preValidatedBlock.idTag}"))
          _ <- OptionT.liftF(blocksWriter.insertBlock(preValidatedBlock.block))
          _ <- OptionT.liftF(log.debug(show"Saved block ${preValidatedBlock.idTag}."))
        } yield preValidatedBlock match {
          case BlockWithValidatedHeader(block) => ImportedBlock(block, fullyValidated = false)
          case ValidatedBlock(block)           => ImportedBlock(block, fullyValidated = true)
        }).value
    }

  override def importAndValidateBlock(unvalidatedBlock: UnvalidatedBlock): F[Option[ImportedBlock]] =
    (for {
      blockWithValidatedHeader <- OptionT(unvalidatedBlock.validate(headerValidator))
      importedBlock            <- OptionT(importBlock(blockWithValidatedHeader))
    } yield importedBlock).value
}
