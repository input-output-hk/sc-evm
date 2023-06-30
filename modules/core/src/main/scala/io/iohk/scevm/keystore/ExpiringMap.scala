package io.iohk.scevm.keystore

import io.iohk.scevm.keystore.ExpiringMap.ValueWithDuration

import java.time.Duration
import java.time.temporal.ChronoUnit
import scala.collection.mutable
import scala.util.Try

object ExpiringMap {

  final case class ValueWithDuration[V](value: V, expiration: Duration)

  def empty[K, V](defaultElementRetentionTime: Duration): ExpiringMap[K, V] =
    new ExpiringMap(mutable.Map.empty, defaultElementRetentionTime)
}

/** Simple wrapper around mutable map which enriches each element with expiration time (specified by user or default)
  * Map is passive which means it only check for expiration and remove expired element during get function.
  * Duration in all calls is relative to current System.nanoTime()
  */
class ExpiringMap[K, V] private (
    val underlying: mutable.Map[K, ValueWithDuration[V]],
    val defaultRetentionTime: Duration
) {
  private val maxHoldDuration = ChronoUnit.CENTURIES.getDuration

  def add(k: K, v: V, duration: Duration): ExpiringMap[K, V] =
    addFor(k, v, duration)

  def addForever(k: K, v: V): ExpiringMap[K, V] =
    addFor(k, v, maxHoldDuration)

  def add(k: K, v: V): ExpiringMap[K, V] =
    addFor(k, v, defaultRetentionTime)

  def remove(k: K): ExpiringMap[K, V] = {
    underlying -= k
    this
  }

  private[keystore] def addFor(k: K, v: V, duration: Duration): ExpiringMap[K, V] = {
    underlying += k -> ValueWithDuration(v, Try(currentPlus(duration)).getOrElse(currentPlus(maxHoldDuration)))
    this
  }

  private def currentPlus(duration: Duration) =
    currentNanoDuration().plus(duration)

  private def currentNanoDuration() =
    Duration.ofNanos(System.nanoTime())

  def get(k: K): Option[V] =
    underlying
      .get(k)
      .flatMap(value =>
        if (isNotExpired(value))
          Some(value.value)
        else {
          remove(k)
          None
        }
      )

  private def isNotExpired(value: ValueWithDuration[V]) =
    currentNanoDuration().minus(value.expiration).isNegative

}
