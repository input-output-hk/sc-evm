package io.iohk.scevm.rpc

import cats.effect.MonadCancelThrow
import cats.effect.kernel.Resource
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.domain.{Address, BlockContext, Nonce, ObftHeader}
import io.iohk.scevm.ledger.NonceProvider

trait NonceService[F[_]] {

  /** Calculates the next nonce for a given address based on the last on-chain nonce
    * and all transactions which are in the mem-pool
    *
    * @param address
    * @return
    */
  def getNextNonce(address: Address): F[Nonce]
}

class NonceServiceImpl[F[_]: MonadCancelThrow](
    nonceProvider: NonceProvider[F],
    bestHeaderProvider: F[ObftHeader],
    worldStateBuilder: WorldStateBuilder[F]
) extends NonceService[F] {
  override def getNextNonce(address: Address): F[Nonce] =
    (for {
      bestHeader <- Resource.eval(bestHeaderProvider)
      world      <- worldStateBuilder.getWorldStateForBlock(bestHeader.stateRoot, BlockContext.from(bestHeader))
    } yield world).use(nonceProvider.getNextNonce(address, _))
}
