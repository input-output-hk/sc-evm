package io.iohk.scevm

import cats.effect.{Async, Resource, Sync}
import cats.syntax.all._
import io.iohk.scevm.metrics.{MetricsConfig, TracingConfig}
import io.janstenpickle.trace4cats.inject.EntryPoint
import io.janstenpickle.trace4cats.kernel.SpanSampler
import io.janstenpickle.trace4cats.model.TraceProcess
import io.janstenpickle.trace4cats.opentelemetry.otlp.OpenTelemetryOtlpHttpSpanCompleter
import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports
import org.http4s.blaze.client.BlazeClientBuilder
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Instrumentation {
  def startMetricsServer[F[_]: Sync](metricsConfig: MetricsConfig): Resource[F, Unit] = {
    implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass(this.getClass)
    if (metricsConfig.enabled) {
      for {
        _ <- Resource.eval(logger.info("Prometheus metrics are enabled"))
        _ <- Resource
               .make(Sync[F].delay(new HTTPServer(metricsConfig.metricsPort))) { server =>
                 logger.info("Stopping metrics server") >> Sync[F].delay(server.close())
               }
               .evalTap(_ => Sync[F].delay(DefaultExports.initialize()))
      } yield ()
    } else {
      Resource.eval(logger.info("Prometheus metrics are disabled"))
    }
  }

  def tracingEntryPoint[F[_]: Async](tracingConfig: TracingConfig): Resource[F, EntryPoint[F]] = {
    implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass(this.getClass)
    if (tracingConfig.enabled)
      for {
        _ <-
          Resource.eval(
            logger.info(
              s"Opentelemetry application tracing is enabled, expecting 'tempo' at http://${tracingConfig.host}:${tracingConfig.port}"
            )
          )
        httpClient <- BlazeClientBuilder[F].resource
        completer <-
          OpenTelemetryOtlpHttpSpanCompleter(httpClient, TraceProcess("sc_evm"), tracingConfig.host, tracingConfig.port)
        entryPoint = EntryPoint(SpanSampler.always[F], completer)
      } yield entryPoint
    else
      Resource.eval(logger.info(s"Opentelemetry application tracing is disabled")) >>
        Resource.pure(EntryPoint.noop)
  }

}
