package io.iohk.scevm.sidechain.observability

import cats.Applicative
import cats.effect.Async
import cats.effect.implicits.genSpawnOps
import cats.effect.kernel.Resource
import cats.syntax.all._
import io.iohk.scevm.sidechain.ObservabilityConfig
import io.iohk.scevm.utils.SystemTime
import org.typelevel.log4cats.{Logger, LoggerFactory}

trait ObserverScheduler[F[_]] {
  def ticker(): F[Unit]
}

object ObserverScheduler {
  def noop[F[_]: Applicative]: ObserverScheduler[F] = () => Applicative[F].unit

  def start[F[_]: Async: SystemTime: LoggerFactory](
      observers: List[Observer[F]],
      config: ObservabilityConfig
  ): Resource[F, Unit] = {
    implicit val logger: Logger[F] = LoggerFactory[F].getLogger
    fs2.Stream
      .eval(
        observers.traverse(
          _.apply().attemptT
            .leftMap(t => logger.warn(t)("Error when running observer"))
            .value
        )
      )
      .repeat
      .metered(config.waitingPeriod)
      .compile
      .drain
      .background
      .void
  }

  trait Observer[F[_]] {
    def apply(): F[Unit]
  }
}
