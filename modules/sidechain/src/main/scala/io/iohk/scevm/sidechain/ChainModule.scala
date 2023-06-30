package io.iohk.scevm.sidechain

import io.iohk.scevm.consensus.pos.BlockPreExecution
import io.iohk.scevm.consensus.validators.PostExecutionValidator
import io.iohk.scevm.domain.Tick
import io.iohk.scevm.exec.vm.{EvmCall, TransactionSimulator}
import io.iohk.scevm.ledger.LeaderElection
import io.iohk.scevm.sync.NextBlockTransactions

trait ChainModule[F[_]]
    extends TransactionSimulator[EvmCall[F, *]]
    with BlockPreExecution[F]
    with LeaderElection[F]
    with NextBlockTransactions[F]
    with PostExecutionValidator[F] {
  def ticker: fs2.Stream[F, Tick]
}
