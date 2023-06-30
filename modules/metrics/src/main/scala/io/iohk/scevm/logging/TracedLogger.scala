package io.iohk.scevm.logging

import cats.Monad
import cats.syntax.all._
import io.janstenpickle.trace4cats.ToHeaders
import io.janstenpickle.trace4cats.inject.Trace
import io.janstenpickle.trace4cats.model.TraceHeaders
import org.typelevel.ci.CIStringSyntax
import org.typelevel.log4cats.StructuredLogger

class TracedLogger[F[_]: Trace: Monad] private (logger: StructuredLogger[F]) extends StructuredLogger[F] {

  val traceCtx: F[Map[String, String]] = Trace[F].headers(ToHeaders.b3).map(TracedLogger.toStandardFields)

  def trace(message: => String): F[Unit] =
    traceCtx.flatMap(map => logger.trace(map)(message))
  def trace(ctx: Map[String, String])(msg: => String): F[Unit] =
    traceCtx.flatMap(map => logger.trace(map ++ ctx)(msg))
  def trace(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] =
    traceCtx.flatMap(map => logger.trace(map ++ ctx, t)(msg))
  def trace(t: Throwable)(message: => String): F[Unit] =
    traceCtx.flatMap(map => logger.trace(map, t)(message))

  def debug(message: => String): F[Unit] =
    traceCtx.flatMap(map => logger.debug(map)(message))
  def debug(ctx: Map[String, String])(msg: => String): F[Unit] =
    traceCtx.flatMap(map => logger.debug(map ++ ctx)(msg))
  def debug(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] =
    traceCtx.flatMap(map => logger.debug(map ++ ctx, t)(msg))
  def debug(t: Throwable)(message: => String): F[Unit] =
    traceCtx.flatMap(map => logger.debug(map, t)(message))

  def info(message: => String): F[Unit] =
    traceCtx.flatMap(map => logger.info(map)(message))
  def info(ctx: Map[String, String])(msg: => String): F[Unit] =
    traceCtx.flatMap(map => logger.info(map ++ ctx)(msg))
  def info(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] =
    traceCtx.flatMap(map => logger.info(map ++ ctx, t)(msg))
  def info(t: Throwable)(message: => String): F[Unit] =
    traceCtx.flatMap(map => logger.info(map, t)(message))

  def warn(message: => String): F[Unit] =
    traceCtx.flatMap(map => logger.warn(map)(message))
  def warn(ctx: Map[String, String])(msg: => String): F[Unit] =
    traceCtx.flatMap(map => logger.warn(map ++ ctx)(msg))
  def warn(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] =
    traceCtx.flatMap(map => logger.warn(map ++ ctx, t)(msg))
  def warn(t: Throwable)(message: => String): F[Unit] =
    traceCtx.flatMap(map => logger.warn(map, t)(message))

  def error(message: => String): F[Unit] =
    traceCtx.flatMap(map => logger.error(map)(message))
  def error(ctx: Map[String, String])(msg: => String): F[Unit] =
    traceCtx.flatMap(map => logger.error(map ++ ctx)(msg))
  def error(ctx: Map[String, String], t: Throwable)(msg: => String): F[Unit] =
    traceCtx.flatMap(map => logger.error(map ++ ctx, t)(msg))
  def error(t: Throwable)(message: => String): F[Unit] =
    traceCtx.flatMap(map => logger.error(map, t)(message))
}

object TracedLogger {

  def apply[F[_]: Trace: Monad](logger: StructuredLogger[F]): StructuredLogger[F] =
    new TracedLogger[F](logger)

  private val traceIdKey: String = "traceId"
  private val spanIdKey: String  = "spanId"

  private def toStandardFields(headers: TraceHeaders): Map[String, String] =
    (
      headers.values
        .get(ci"X-B3-SpanId")
        .filterNot(_ == "0000000000000000") // exclude no-op spans
        .map(spanIdKey -> _) ++
        headers.values
          .get(ci"X-B3-TraceId")
          .filterNot(_ == "00000000000000000000000000000000") // exclude no-op traces
          .map(traceIdKey -> _)
    ).toMap
}
