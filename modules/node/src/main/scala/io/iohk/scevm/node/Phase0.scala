package io.iohk.scevm.node

import cats.effect.Resource
import cats.effect.kernel.Sync
import cats.syntax.all._
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.pos.PrometheusPoSMetrics
import io.iohk.scevm.db.DataSourceConfig
import io.iohk.scevm.db.storage.genesis.{ObftGenesisLoader, ObftGenesisValidation}
import io.iohk.scevm.db.storage.{ObftStorageBuilder, StableNumberMappingStorage}
import io.iohk.scevm.domain.ObftBlock
import io.iohk.scevm.storage.metrics.StorageMetrics
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Phase0 is responsible for:
  * - storage initialization
  *     - genesis initialization if needed
  *     - stable mapping initialization
  * - PoS metrics initialization
  */
object Phase0 {

  def run[F[_]: Sync](
      blockchainConfig: BlockchainConfig,
      dbConfig: DataSourceConfig,
      storageMetrics: StorageMetrics[F]
  ): Resource[F, Phase0Outcome[F]] = {
    val logger = Slf4jLogger.getLogger[F]
    ObftStorageBuilder
      .create[F](dbConfig, storageMetrics)
      .evalMap { obftStorageBuilder =>
        for {
          genesis <-
            ObftGenesisLoader.load[F](obftStorageBuilder.evmCodeStorage, obftStorageBuilder.stateStorage)(
              blockchainConfig.genesisData
            )
          _ <- ObftGenesisValidation.initGenesis[F](
                 obftStorageBuilder.stableNumberMappingStorage.getBlockHash,
                 obftStorageBuilder.blockchainStorage,
                 obftStorageBuilder.blockchainStorage,
                 obftStorageBuilder.receiptStorage
               )(genesis)
          _ <- logger.info(s"Genesis block: ${genesis.fullToString}")
          _ <- StableNumberMappingStorage.init[F](obftStorageBuilder.stableNumberMappingStorage)(genesis)
        } yield Phase0Outcome(genesis, obftStorageBuilder)
      }
      .flatTap(_ => registerPoSConfig(blockchainConfig))
  }

  private def registerPoSConfig[F[_]: Sync](blockchainConfig: BlockchainConfig): Resource[F, Unit] =
    PrometheusPoSMetrics[F].evalMap { posMetrics =>
      for {
        _ <- posMetrics.stabilityParameterGauge.set(blockchainConfig.stabilityParameter)
        _ <- posMetrics.slotDurationGauge.set(blockchainConfig.slotDuration.toSeconds)
      } yield ()
    }

  final case class Phase0Outcome[F[_]](genesis: ObftBlock, storage: ObftStorageBuilder[F])

}
