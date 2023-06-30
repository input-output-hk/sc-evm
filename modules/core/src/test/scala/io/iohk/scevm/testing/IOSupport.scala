package io.iohk.scevm.testing

import cats.effect.IO
import cats.effect.unsafe.IORuntime
import org.scalactic.source
import org.scalatest.compatible.Assertion
import org.scalatest.concurrent.ScalaFutures
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

trait IOSupport { self: ScalaFutures =>
  implicit val global: IORuntime = IORuntime.global

  def assertIO(io: => IO[Assertion])(implicit pos: source.Position): Future[Assertion] =
    io.unsafeToFuture()

  def assertIOSync(io: => IO[Assertion])(implicit timeout: Duration, pos: source.Position): Unit =
    Await.result(io.unsafeToFuture(), timeout)

  implicit class RichIO[A](val IO: IO[A]) {
    def ioValue(implicit patienceConfig: PatienceConfig, pos: source.Position): A = IO.unsafeToFuture().futureValue
  }

  implicit val loggerFactory: LoggerFactory[IO] = NoOpFactory[IO]
}
