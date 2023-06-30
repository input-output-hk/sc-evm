package io.iohk.scevm.ledger

import cats.Monad
import cats.data.OptionT
import cats.syntax.all._
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.vm.WorldType

trait NonceProvider[F[_]] {

  /** Calculates the next nonce for a given address based on the last on-chain nonce
    * and all transactions which are in the mem-pool for a given world
    */
  def getNextNonce(address: Address, world: WorldType): F[Nonce]
}

class NonceProviderImpl[F[_]: Monad](
    getAllFromMemPool: F[List[SignedTransaction]],
    blockchainConfig: BlockchainConfig
) extends NonceProvider[F] {
  def getNextNonce(address: Address, world: WorldType): F[Nonce] = {
    implicit val chainId: BlockchainConfig.ChainId = blockchainConfig.chainId

    val latestMempoolNonce: F[Option[Nonce]] = for {
      pendingTxsFuture <- getAllFromMemPool
      latestPendingTxNonce = pendingTxsFuture.collect {
                               case tx if SignedTransaction.getSender(tx).contains(address) =>
                                 tx.transaction.nonce
                             }
    } yield latestPendingTxNonce.maxOption

    def latestChainNonce: Nonce =
      world.getAccount(address).map(_.nonce).getOrElse(blockchainConfig.genesisData.accountStartNonce)

    OptionT(latestMempoolNonce)
      .map(_.increaseOne)
      .getOrElse(latestChainNonce)
  }
}
