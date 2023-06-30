package io.iohk.dataGenerator.fixtures

import cats.effect.IO
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionResult
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.{EvmCodeStorageImpl, NodeStorage, StateStorage}
import io.iohk.scevm.domain.{BlockContext, Nonce, ObftHeader, SignedTransaction}
import io.iohk.scevm.exec.vm.InMemoryWorldState
import io.iohk.scevm.ledger.{BlockPreparation, PreparedBlock}

object NoOpBlockPreparation extends BlockPreparation[IO] {
  override def prepareBlock(
      blockContext: BlockContext,
      transactionList: Seq[SignedTransaction],
      parent: ObftHeader
  ): IO[PreparedBlock] = {
    val dataSource = EphemDataSource()
    val worldState = InMemoryWorldState(
      evmCodeStorage = new EvmCodeStorageImpl(dataSource),
      mptStorage = StateStorage(new NodeStorage(dataSource)).archivingStorage,
      getBlockHashByNumber = (_, _) => None,
      accountStartNonce = Nonce(0),
      stateRootHash = ByteString.empty,
      noEmptyAccounts = false,
      blockContext = blockContext
    )

    PreparedBlock(
      transactionList = Seq.empty,
      blockResult = BlockExecutionResult(worldState),
      stateRootHash = ByteString.empty,
      updatedWorld = worldState
    ).pure[IO]
  }
}
