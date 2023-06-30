package io.iohk.scevm.consensus.pos

import cats.MonadThrow
import cats.syntax.all._
import io.iohk.scevm.db.storage.{BlocksReader, BranchProvider, ObftAppStorage}
import io.iohk.scevm.domain.{BlockHash, ObftBlock}
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}

object Consensus {
  def init[F[_]: MonadThrow: LoggerFactory](
      blockReader: BlocksReader[F],
      branchProvider: BranchProvider[F],
      appStorage: ObftAppStorage[F]
  ): fs2.Stream[F, ObftBlock] = {
    val logger: SelfAwareStructuredLogger[F] = LoggerFactory[F].getLogger
    fs2.Stream
      .eval(appStorage.getLatestStableBlockHashOrFail)
      .evalTap { stableHash =>
        blockReader.getBlock(stableHash) >>= {
          case Some(stableBlock) =>
            logger.info(show"Initialization of best block started with stable block $stableBlock")
          case None => logger.info(show"Initialization of best block started without a stable block")
        }
      }
      .flatMap { stableHash =>
        fs2.Stream
          .evalSeq(branchProvider.getChildren(stableHash))
          .evalMap { header =>
            logger.debug(show"Initialization with child=$header") >>
              blockReader.getBlock(header.hash).flatMap(MonadThrow[F].fromOption(_, MissingChild(header.hash)))
          }
      }
  }

  final case class MissingChild(hash: BlockHash)
      extends Exception(s"technical error: entire block with hash=${hash.toHex} could not be found")
}
