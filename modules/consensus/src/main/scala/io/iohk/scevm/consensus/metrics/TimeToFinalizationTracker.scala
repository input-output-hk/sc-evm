package io.iohk.scevm.consensus.metrics

import cats.effect.{Ref, Sync}
import cats.syntax.all._
import io.iohk.scevm.consensus.pos.PoSConfig.Metrics
import io.iohk.scevm.domain.BlockHash
import io.iohk.scevm.utils.SystemTime
import io.iohk.scevm.utils.SystemTime.UnixTimestamp

import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}

/** Tracks when a block is first seen, in order to compute the time needed for the block to become stable.
  */
trait TimeToFinalizationTracker[F[_]] {
  def track(blockHash: BlockHash): F[Unit]
  def evaluate(blockHash: BlockHash): F[Option[FiniteDuration]]
}

object TimeToFinalizationTracker {
  def noop[F[_]: Sync]: TimeToFinalizationTracker[F] = new TimeToFinalizationTracker[F] {
    override def track(blockHash: BlockHash): F[Unit]                      = Sync[F].unit
    override def evaluate(blockHash: BlockHash): F[Option[FiniteDuration]] = Sync[F].pure(None)
  }
}

class TimeToFinalizationTrackerImpl[F[_]: Sync: SystemTime] private (
    private val headerTrackerMap: Ref[F, Map[BlockHash, UnixTimestamp]],
    timeout: FiniteDuration
) extends TimeToFinalizationTracker[F] {

  /** Start tracking the given hash
    *
    * Note: if the blockHash is already tracked, keep the time when it was first seen
    */
  override def track(blockHash: BlockHash): F[Unit] =
    for {
      currentTime <- SystemTime[F].realTime()
      _ <- headerTrackerMap.getAndUpdate { map =>
             map.updatedWith(blockHash) {
               case existingTime: Some[_] => existingTime
               case None                  => Some(currentTime)
             }
           }
    } yield ()

  /** Compute the elapsed time since the given hash started being tracked.
    *
    * Note:
    * Others hashes that have been tracked without being evaluated for a too long period will be untracked.
    * It will be the case for invalid Block that may not be imported.
    */
  override def evaluate(blockHash: BlockHash): F[Option[FiniteDuration]] =
    for {
      currentTime <- SystemTime[F].realTime()
      oldMap <- headerTrackerMap.getAndUpdate { map =>
                  map
                    .removed(blockHash)
                    .filter { case (_, startTime) => currentTime < startTime.add(timeout) }
                }
    } yield oldMap
      .get(blockHash)
      .map(startTime => (currentTime.millis - startTime.millis).millis)
}

object TimeToFinalizationTrackerImpl {
  def apply[F[_]: Sync: SystemTime](metricsConfig: Metrics): F[TimeToFinalizationTracker[F]] =
    if (metricsConfig.timeToFinalizationTrackerTimeout > 0.seconds) {
      for {
        hashToNumberMap <- Ref.of[F, Map[BlockHash, UnixTimestamp]](Map.empty)
      } yield new TimeToFinalizationTrackerImpl[F](hashToNumberMap, metricsConfig.timeToFinalizationTrackerTimeout)
    } else {
      Sync[F].pure(TimeToFinalizationTracker.noop[F])
    }
}
