package io.iohk.scevm.utils

import cats.effect.Sync
import cats.syntax.all._
import cats.{Applicative, Show, effect}
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops.toCoercibleIdOps
import io.iohk.ethereum.rlp.{RLPDecoder, RLPEncodeable, RLPEncoder}
import io.iohk.scevm.serialization.Newtype
import io.iohk.scevm.utils.SystemTime.UnixTimestamp
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.InstantToUnixTimestamp

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/** A custom replacement for cats.Clock
  * The raison d'etre is: this time has to be common between all validators.
  * This means it has to refer to a common TZ.
  *
  * The `live` version chooses UTC as the common TZ.
  */
trait SystemTime[F[_]] {
  def realTime(): F[UnixTimestamp]
}

object SystemTime {
  import scala.language.implicitConversions

  def apply[F[_]](implicit instance: SystemTime[F]): SystemTime[F] = instance

  def liveF[F[_]: Sync]: F[SystemTime[F]] = Sync[F].delay(new CatsBased[F])

  @newtype final case class UnixTimestamp(val millis: Long) {
    def add(delay: FiniteDuration): UnixTimestamp = UnixTimestamp.fromMillis(millis + delay.toMillis)
    def sub(delay: FiniteDuration): UnixTimestamp = UnixTimestamp.fromMillis(millis - delay.toMillis)

    def toSeconds: Long                      = millis / 1000
    def toTimestampSeconds: TimestampSeconds = TimestampSeconds.fromSeconds(toSeconds)
  }

  object UnixTimestamp {
    def fromSeconds(seconds: Long): UnixTimestamp = (seconds * 1000).coerce
    def fromMillis(millis: Long): UnixTimestamp   = millis.coerce

    implicit val show: Show[UnixTimestamp]                          = deriving
    implicit val ordering: Ordering[UnixTimestamp]                  = Ordering.by(_.millis)
    implicit def ordered(ts: UnixTimestamp): Ordered[UnixTimestamp] = Ordered.orderingToOrdered(ts)(ordering)

    implicit val valueClass: Newtype[UnixTimestamp, Long] =
      Newtype[UnixTimestamp, Long](UnixTimestamp.fromMillis, _.millis)

    implicit object UnixTimestampEncDec extends RLPEncoder[UnixTimestamp] with RLPDecoder[UnixTimestamp] {
      import io.iohk.ethereum.rlp.RLPImplicits.longEncDec
      override def encode(obj: UnixTimestamp): RLPEncodeable = longEncDec.encode(obj.millis)
      override def decode(rlp: RLPEncodeable): UnixTimestamp = longEncDec.decode(rlp).millisToTs
    }

    implicit class LongToUnixTimestamp(val ts: Long) extends AnyVal {
      //TODO require ts >= 0
      def millisToTs: UnixTimestamp = ts.coerce
    }

    implicit class FiniteDurationToUnixTimestamp(val ts: FiniteDuration) extends AnyVal {
      //TODO require ts >= 0
      def asTs: UnixTimestamp = ts.toMillis.coerce
    }

    implicit class InstantToUnixTimestamp(val instant: Instant) extends AnyVal {
      def toTs: UnixTimestamp = instant.toEpochMilli.millisToTs
    }

    implicit class UnixTimestampOps(val ts: UnixTimestamp) extends AnyVal {
      @inline def seconds: Long = ts.millis / 1000
    }
  }

  @newtype final case class TimestampSeconds(seconds: BigInt)

  object TimestampSeconds {
    def fromSeconds(seconds: BigInt): TimestampSeconds = seconds.coerce
  }

  final private class CatsBased[F[_]: effect.Clock: Applicative] extends SystemTime[F] {
    override def realTime(): F[UnixTimestamp] =
      effect.Clock[F].realTimeInstant.map(_.toTs)
  }
}
