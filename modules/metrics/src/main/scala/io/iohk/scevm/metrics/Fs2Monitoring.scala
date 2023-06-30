package io.iohk.scevm.metrics

import cats.effect.Sync
import cats.effect.kernel.{Async, Clock}
import cats.effect.std.Queue
import cats.implicits._
import fs2.Stream
import io.prometheus.client.{Counter, Gauge, Histogram}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration._

object Fs2Monitoring {

  private val warningThreshold = 100

  private val namespace = "sc_evm"

  private val StreamEventCounter = Counter
    .build()
    .namespace(namespace)
    .name("fs2_stream_item_count")
    .help("Total count of published items in the stream so far")
    .labelNames("stream_name")
    .register()

  private val StreamBufferSizeGauge = Gauge
    .build()
    .namespace(namespace)
    .name("fs2_stream_buffered_items")
    .help("Buffered item in the stream.")
    .labelNames("stream_name")
    .register()

  private val StreamBufferWaitTimeHistogram = Histogram
    .build()
    .namespace(namespace)
    .name("fs2_stream_item_wait_time_seconds")
    .help("Duration between the time an item is added in the monitoring queue and when it is picked up by the stream.")
    .labelNames("stream_name")
    .buckets(1e-6, 1e-5, 1e-4, 1e-3, 1e-2, 1e-1, 1)
    .register()

  /** Adds instrumentation to a stream to check the number of items going through and if the stream is backpressuring */
  def probe[F[_]: Async, T](name: String): fs2.Pipe[F, T, T] = stream => {
    val waitTimeHistogram = StreamBufferWaitTimeHistogram.labels(name)
    val eventCounter      = StreamEventCounter.labels(name)
    for {
      queue <- Stream.eval(Queue.unbounded[F, Option[(T, Long)]])
      value <- Stream
                 .fromQueueNoneTerminated(queue)
                 .concurrently(feedQueueFromStream(name, stream, queue))
                 .concurrently(monitorQueueSize(name, queue))
      (item, addedAtMs) = value
      poppedAtMs       <- Stream.eval(Clock[F].monotonic.map(_.toMicros))
      _ <- Stream.eval(
             Sync[F].delay(waitTimeHistogram.observe((poppedAtMs - addedAtMs).toDouble / 1e6))
           )
      _ <- Stream.eval(Sync[F].delay(eventCounter.inc()))
    } yield item
  }

  private def feedQueueFromStream[T, F[_]: Async](
      name: String,
      stream: Stream[F, T],
      queue: Queue[F, Option[(T, Long)]]
  ) = {
    val sizeGauge = StreamBufferSizeGauge.labels(name)
    stream
      .evalMap(item =>
        for {
          start <- Clock[F].monotonic.map(_.toMicros)
          _     <- queue.offer(Some((item, start)))
          _     <- queue.size.map(sizeGauge.set(_))
        } yield ()
      )
      .drain
      .onFinalize(queue.offer(None))
  }

  private def logOverflowingStream[T, F[_]: Sync](name: String, log: Logger[F])(s: Int) =
    if (s > warningThreshold) log.warn(s"Stream $name has $s buffered elements")
    else
      Sync[F].unit

  private def monitorQueueSize[T, F[_]: Async](name: String, queue: Queue[F, T]): Stream[F, Unit] = {
    val logBufferSize: Int => F[Unit] = logOverflowingStream(name, Slf4jLogger.getLoggerFromClass(getClass))(_)
    Stream
      .awakeEvery(5.seconds)
      .evalMap(_ => queue.size.flatMap(logBufferSize))
  }

}
