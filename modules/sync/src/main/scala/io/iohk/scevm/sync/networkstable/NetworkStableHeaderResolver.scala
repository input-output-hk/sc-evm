package io.iohk.scevm.sync.networkstable

import cats.Monad
import cats.effect.{Async, Sync}
import cats.implicits.{catsSyntaxApplicativeId, catsSyntaxFlatMapOps, toFlatMapOps}
import fs2.concurrent.Signal
import io.iohk.scevm.domain.BlockNumber
import io.iohk.scevm.network.{PeerId, PeerWithInfo}
import io.iohk.scevm.sync.networkstable.NetworkStableHeaderResult.{NoAvailablePeer, StableHeaderFound}
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.FiniteDuration

trait NetworkStableHeaderResolver[F[_]] {

  /** Resolve the network stable header according to some business logic.
    */
  def resolve(
      peersWithInfo: Signal[F, Map[PeerId, PeerWithInfo]]
  ): F[NetworkStableHeaderResult]
}

object NetworkStableHeaderResolver {

  val MINIMUM_HIGH_DENSITY_STABLE_SCORE: Double = 2.0 / 3

  /** This resolver looks for the stable header with the highest block number
    * and a density above 2/3.
    * - the 2/3 threshold is used to guarantee that the chain is 'honest' (no malicious or technical misbehavior)
    * - the highest block number is used to sync the further away
    * - the density scoring is only used to filter candidates, but is not used to order them
    *
    * OBFT guarantees protection against malicious intent when more
    * than 2/3 of the slot leaders are honest.
    * By enforcing a minimum density above 2/3, the synchronization
    * targets an honest and valid chain. Under that threshold, it can't
    * distinguish between malicious construct or honest participation
    * issues (like network split, downtime, etc)
    */
  def highDensity[F[_]: Sync]: NetworkStableHeaderResolver[F] = {
    val logger = Slf4jLogger.getLoggerFromName("HighDensityResolver")
    new DensityThresholdResolver(
      logger,
      MINIMUM_HIGH_DENSITY_STABLE_SCORE,
      Ordering.by[StableHeaderFound, BlockNumber] { case StableHeaderFound(stableHeader, _, _) =>
        stableHeader.number
      }
    )
  }

  /** Look for the stable header with the highest score.
    * The density should be above the minimum configurable threshold.
    * If multiples headers share the same score, it returns the one with the highest block number.
    */
  def lowDensity[F[_]: Sync](minimumThreshold: Double): NetworkStableHeaderResolver[F] = {
    val logger = Slf4jLogger.getLoggerFromName("LowDensityResolver")
    new DensityThresholdResolver(
      logger,
      minimumThreshold,
      Ordering.by[StableHeaderFound, (Double, BlockNumber)] { case StableHeaderFound(stableHeader, score, _) =>
        (score, stableHeader.number)
      }
    )
  }

  /** Produce a new NetworkStableHeaderResolver that uses the second resolver as a fallback in case of error with
    * the first one
    */
  def withFallback[F[_]: Monad](
      initial: NetworkStableHeaderResolver[F],
      fallback: NetworkStableHeaderResolver[F]
  ): NetworkStableHeaderResolver[F] = (peersWithInfo: Signal[F, Map[PeerId, PeerWithInfo]]) =>
    initial.resolve(peersWithInfo).flatMap {
      case _: NetworkStableHeaderError => fallback.resolve(peersWithInfo)
      case other                       => other.pure
    }

  /** Produce a new NetworkStableResolver that uses a delegate resolver and retries certain number of times in case of error
    */
  def withRetry[F[_]: Async](
      retryCount: Int,
      retryDelay: FiniteDuration,
      delegate: NetworkStableHeaderResolver[F]
  ): NetworkStableHeaderResolver[F] = {
    val logger = Slf4jLogger.getLoggerFromName("withRetry")
    (peersWithInfo: Signal[F, Map[PeerId, PeerWithInfo]]) => {

      def loop(loopRetryCount: Int): F[NetworkStableHeaderResult] =
        delegate.resolve(peersWithInfo).flatMap {
          case NoAvailablePeer if loopRetryCount == 0 =>
            logger.debug(s"No peers available after ${retryCount} tentatives") >>
              Monad[F].pure(NoAvailablePeer)
          case error if loopRetryCount == 0 =>
            logger.debug(s"No header found after ${retryCount} tentatives") >>
              Monad[F].pure(error)
          case success: NetworkStableHeaderSuccess => Monad[F].pure(success)
          case _ =>
            logger.debug(
              s"Unable to find a proper network stable header result, retrying ($loopRetryCount remaining tentatives)..."
            ) >>
              Async[F].sleep(retryDelay) >>
              loop(loopRetryCount - 1)
        }

      loop(retryCount)
    }
  }
}
