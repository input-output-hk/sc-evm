package io.iohk.scevm.rpc

import cats.Monad
import io.iohk.scevm.db.dataSource.DataSourceBatchUpdate
import io.iohk.scevm.db.storage.EvmCodeStorageImpl.{Code, CodeHash}
import io.iohk.scevm.db.storage._
import io.iohk.scevm.domain
import io.iohk.scevm.domain.{BlockHash, ObftBlock, ObftBody, ObftHeader, Receipt, TransactionHash, TransactionLocation}
import io.iohk.scevm.rpc.controllers.EthTransactionController.ReceiptReader
import io.iohk.scevm.rpc.controllers.SanityController.StableNumberMappingReader

trait Storage[F[_]]
    extends BlocksReader[F]
    with EvmCodeStorage
    with StableNumberMappingReader[F]
    with ReceiptReader[F]
    with GetTransactionBlockService[F]

object Storage {
  def apply[F[_]: Monad](
      obftStorageBuilder: ObftStorageBuilder[F]
  ): Storage[F] = new Storage[F] {
    private val txBlockService =
      new GetTransactionBlockServiceImpl[F](
        obftStorageBuilder.blockchainStorage,
        obftStorageBuilder.transactionMappingStorage
      )

    override def getReceiptsByHash(hash: BlockHash): F[Option[Seq[Receipt]]] =
      obftStorageBuilder.receiptStorage.getReceiptsByHash(hash)

    override def getBlockHash(number: domain.BlockNumber): F[Option[BlockHash]] =
      obftStorageBuilder.stableNumberMappingStorage.getBlockHash(number)

    override def getBlockHeader(hash: BlockHash): F[Option[ObftHeader]] =
      obftStorageBuilder.blockchainStorage.getBlockHeader(hash)

    override def getBlockBody(hash: BlockHash): F[Option[ObftBody]] =
      obftStorageBuilder.blockchainStorage.getBlockBody(hash)

    override def getBlock(hash: BlockHash): F[Option[ObftBlock]] = obftStorageBuilder.blockchainStorage.getBlock(hash)

    override def get(key: CodeHash): Option[Code] = obftStorageBuilder.evmCodeStorage.get(key)

    override def put(key: CodeHash, value: Code): DataSourceBatchUpdate =
      obftStorageBuilder.evmCodeStorage.put(key, value)

    /** Find a transaction inside an alternative branch (represented by its tip hash) */
    override def getTransactionLocation(stable: ObftHeader, best: BlockHash)(
        transactionHash: TransactionHash
    ): F[Option[TransactionLocation]] = txBlockService.getTransactionLocation(stable, best)(transactionHash)
  }
}
