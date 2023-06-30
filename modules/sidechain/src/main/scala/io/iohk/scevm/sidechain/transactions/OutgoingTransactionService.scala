package io.iohk.scevm.sidechain.transactions

import cats.effect.MonadCancelThrow
import cats.implicits._
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.domain.BlockContext
import io.iohk.scevm.exec.vm.EvmCall
import io.iohk.scevm.sidechain.{BridgeContract, SidechainEpoch}

class OutgoingTransactionService[F[_]: MonadCancelThrow: CurrentBranch.Signal](
    bridgeContract: BridgeContract[EvmCall[F, *], _],
    worldStateBuilder: WorldStateBuilder[F]
) {

  def getOutgoingTransaction(epoch: SidechainEpoch): F[Seq[OutgoingTransaction]] =
    for {
      bestHeader <- CurrentBranch.best[F]
      outgoingTransactions <- worldStateBuilder
                                .getWorldStateForBlock(bestHeader.stateRoot, BlockContext.from(bestHeader))
                                .use(bridgeContract.getTransactionsInBatch(epoch.number).run)

    } yield outgoingTransactions
}
