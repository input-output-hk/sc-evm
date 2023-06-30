package io.iohk.scevm.sidechain.observability

import cats.Monad
import cats.syntax.all._
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.scevm.cardanofollower.CardanoFollowerConfig
import io.iohk.scevm.cardanofollower.datasource.MainchainDataSource
import io.iohk.scevm.sidechain.metrics.SidechainMetrics
import io.iohk.scevm.sidechain.{MainchainEpochDerivation, ObservabilityConfig}
import io.iohk.scevm.trustlesssidechain.cardano.MainchainSlot
import io.iohk.scevm.utils.SystemTime
import org.typelevel.log4cats.{Logger, LoggerFactory, SelfAwareStructuredLogger}

class MainchainSlotLagObserver[F[_]: Monad: SystemTime: LoggerFactory, SignatureScheme <: AbstractSignatureScheme](
    mainchainEpochDerivation: MainchainEpochDerivation,
    cardanoFollower: MainchainDataSource[F, SignatureScheme],
    metrics: SidechainMetrics[F],
    observerConfig: ObservabilityConfig,
    followerConfig: CardanoFollowerConfig
) extends ObserverScheduler.Observer[F] {
  implicit private val logF: SelfAwareStructuredLogger[F] = LoggerFactory[F].getLogger
  private val warningThreshold: Double =
    (followerConfig.slotDuration.toSeconds / followerConfig.activeSlotCoeff.doubleValue) * observerConfig.slotLagWarningThresholdInBlocks

  /** Compute the number of slots between the current mainchain slot and the latest slot available in DbSync
    */
  override def apply(): F[Unit] =
    for {
      timestamp    <- SystemTime[F].realTime()
      mainchainSlot = mainchainEpochDerivation.getMainchainSlot(timestamp)
      latestMainchainSlotAvailable <- cardanoFollower.getLatestBlockInfo.map(
                                        _.map(_.slot).getOrElse(MainchainSlot(0L))
                                      )
      lag = mainchainSlot.number - latestMainchainSlotAvailable.number
      _ <-
        if (lag >= warningThreshold) {
          // scalastyle:off line.size.limit
          Logger[F].warn(
            s"There are $lag slots that are not available in mainchain follower (current slot = ${mainchainSlot.number}, latest available = ${latestMainchainSlotAvailable.number})"
          )
          // scalastyle:on line.size.limit
        } else {
          ().pure[F]
        }
      _ <- metrics.mainchainFollowerSlotLag.set(lag.toDouble)
    } yield ()
}
