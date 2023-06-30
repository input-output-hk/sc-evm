package io.iohk.scevm.db.storage.genesis

import cats.data.OptionT
import cats.effect.Sync
import cats.syntax.all._
import io.iohk.scevm.db.storage.{BlocksReader, BlocksWriter, ReceiptStorage}
import io.iohk.scevm.domain.{BlockHash, BlockNumber, ObftBlock}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object ObftGenesisValidation {
  def initGenesis[F[_]: Sync](
      hashByNumber: BlockNumber => F[Option[BlockHash]],
      blocksStorageReader: BlocksReader[F],
      blocksWriter: BlocksWriter[F],
      receiptStorage: ReceiptStorage[F]
  )(genesis: ObftBlock): F[Unit] = {

    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger

    def getBlock0: F[Option[ObftBlock]] = (for {
      blockHash <- OptionT(hashByNumber(BlockNumber(0)))
      block     <- OptionT(blocksStorageReader.getBlock(blockHash))
    } yield block).value

    val saveGenesis: F[Unit] =
      blocksWriter.insertBlock(genesis) >>
        receiptStorage.putReceipts(genesis.hash, Nil)

    def checkGenesis(storedGenesis: ObftBlock): F[Unit] =
      Sync[F].raiseWhen(storedGenesis != genesis)(
        new RuntimeException(show"Incompatible genesis: config=$genesis, storage=$storedGenesis")
      )

    for {
      genesisFromStorage <- getBlock0
      status              = if (genesisFromStorage.isEmpty) "empty" else "existing"
      _                  <- logger.info(show"Starting from $status storage")
      _                  <- genesisFromStorage.fold(saveGenesis)(checkGenesis)
    } yield ()
  }
}
