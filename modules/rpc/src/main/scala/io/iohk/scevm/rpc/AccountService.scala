package io.iohk.scevm.rpc

import cats.effect.MonadCancelThrow
import cats.syntax.all._
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.domain.{Account, Address, BlockContext, ObftHeader}

trait AccountService[F[_]] {
  def getAccount(header: ObftHeader, address: Address): F[Account]
}

class AccountServiceImpl[F[_]: MonadCancelThrow](
    worldStateBuilder: WorldStateBuilder[F],
    blockchainConfig: BlockchainConfig
) extends AccountService[F] {
  override def getAccount(header: ObftHeader, address: Address): F[Account] =
    worldStateBuilder
      .getWorldStateForBlock(header.stateRoot, BlockContext.from(header))
      .use(world =>
        world.getAccount(address).getOrElse(Account.empty(blockchainConfig.genesisData.accountStartNonce)).pure[F]
      )
}
