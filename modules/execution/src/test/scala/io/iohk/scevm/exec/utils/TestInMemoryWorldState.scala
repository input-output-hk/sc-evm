package io.iohk.scevm.exec.utils

import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.{ArchiveStateStorage, EvmCodeStorageImpl, NodeStorage}
import io.iohk.scevm.domain.BlockContext
import io.iohk.scevm.exec.config.EvmConfig
import io.iohk.scevm.exec.vm.InMemoryWorldState
import io.iohk.scevm.metrics.instruments.Histogram
import io.iohk.scevm.testing.{BlockGenerators, TestCoreConfigs}
import org.scalacheck.Gen

object TestInMemoryWorldState {
  val worldStateGen: Gen[InMemoryWorldState] =
    BlockGenerators.obftBlockGen.map { genesis =>
      val dataSource: EphemDataSource = EphemDataSource()
      val stateStorage = new ArchiveStateStorage(
        new NodeStorage(dataSource, Histogram.noOpClientHistogram(), Histogram.noOpClientHistogram())
      )
      val codeStorage =
        new EvmCodeStorageImpl(dataSource, Histogram.noOpClientHistogram(), Histogram.noOpClientHistogram())
      InMemoryWorldState(
        evmCodeStorage = codeStorage,
        mptStorage = stateStorage.archivingStorage,
        getBlockHashByNumber = (_, _) => Some(genesis.hash),
        accountStartNonce = TestCoreConfigs.blockchainConfig.genesisData.accountStartNonce,
        stateRootHash = genesis.header.stateRoot,
        noEmptyAccounts = EvmConfig.forBlock(genesis.number.next, TestCoreConfigs.blockchainConfig).noEmptyAccounts,
        blockContext = BlockContext.from(genesis.header)
      )
    }
}
