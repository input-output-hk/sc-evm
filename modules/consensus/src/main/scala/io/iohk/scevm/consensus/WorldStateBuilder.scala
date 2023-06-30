package io.iohk.scevm.consensus

import cats.effect.std.Dispatcher
import cats.effect.{Async, Resource}
import io.iohk.bytes.ByteString
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.db.storage.{EvmCodeStorage, GetByNumberService, StateStorage}
import io.iohk.scevm.domain.{BlockContext, BlockHash, BlockNumber}
import io.iohk.scevm.exec.config.EvmConfig
import io.iohk.scevm.exec.vm.InMemoryWorldState

trait WorldStateBuilder[F[_]] {

  def getWorldStateForBlock(stateRoot: ByteString, blockContext: BlockContext): Resource[F, InMemoryWorldState]
}

class WorldStateBuilderImpl[F[_]: Async](
    evmCodeStorage: EvmCodeStorage,
    getByNumberService: GetByNumberService[F],
    stateStorage: StateStorage,
    blockchainConfig: BlockchainConfig
) extends WorldStateBuilder[F] {

  def getWorldStateForBlock(stateRoot: ByteString, blockContext: BlockContext): Resource[F, InMemoryWorldState] =
    Dispatcher[F].map { d =>
      def hashByNumber(tip: BlockHash, n: BlockNumber): Option[BlockHash] =
        d.unsafeRunSync(getByNumberService.getHashByNumber(tip)(n))

      InMemoryWorldState(
        evmCodeStorage = evmCodeStorage,
        mptStorage = stateStorage.archivingStorage,
        getBlockHashByNumber = hashByNumber,
        accountStartNonce = blockchainConfig.genesisData.accountStartNonce,
        stateRootHash = stateRoot,
        noEmptyAccounts = EvmConfig.forBlock(blockContext.number, blockchainConfig).noEmptyAccounts,
        blockContext = blockContext
      )
    }
}
