package io.iohk.scevm.tracing

import cats.effect.{IO, IOLocal, Resource}
import cats.syntax.all._
import io.iohk.scevm.tracing.TracingContext.ContextValue
import io.janstenpickle.trace4cats.inject.{EntryPoint, Trace}
import io.janstenpickle.trace4cats.model.{AttributeValue, SpanKind, SpanStatus, TraceHeaders}
import io.janstenpickle.trace4cats.{ErrorHandler, Span, ToHeaders}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class TracingContext private (local: IOLocal[ContextValue]) extends Trace[IO] {
  private val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLoggerFromClass(this.getClass)
  private def logError =
    logger.error(s"Unsupported method call on an empty context. Create span first. This is a bug in the application")

  /* A common label is needed to correlate traces with logs */
  private val appKey: String   = "app"
  private val appLabel: String = "sc_evm"

  import TracingContext.{Empty, Initialized}
  override def put(key: String, value: AttributeValue): IO[Unit] =
    local.get.flatMap {
      case Empty(_) => logError
      case Initialized(span) =>
        span.put(key, value)
    }

  override def putAll(fields: (String, AttributeValue)*): IO[Unit] =
    local.get.flatMap {
      case Empty(_)          => logError
      case Initialized(span) => span.putAll(fields: _*)
    }

  override def span[A](name: String, kind: SpanKind, errorHandler: ErrorHandler)(fa: IO[A]): IO[A] =
    local.get.flatMap {
      case Empty(entryPoint) =>
        entryPoint
          .root(name, kind, errorHandler)
          .flatMap { child =>
            def acquire: IO[Unit] = child.put(appKey, appLabel) >> local.set(Initialized(child))
            def release: IO[Unit] = local.set(Empty(entryPoint))

            Resource.make(acquire)(_ => release)
          }
          .use(_ => fa)
      case Initialized(parentSpan) =>
        parentSpan
          .child(name, kind, errorHandler)
          .flatMap { child =>
            def acquire: IO[Unit] = child.put(appKey, appLabel) >> local.set(Initialized(child))
            def release: IO[Unit] = local.set(Initialized(parentSpan))

            Resource.make(acquire)(_ => release)
          }
          .use(_ => fa)
    }

  override def headers(toHeaders: ToHeaders): IO[TraceHeaders] =
    local.get.flatMap {
      case Empty(_)          => TraceHeaders.empty.pure[IO]
      case Initialized(span) => toHeaders.fromContext(span.context).pure[IO]
    }

  override def setStatus(status: SpanStatus): IO[Unit] =
    local.get.flatMap {
      case Empty(_)          => logError
      case Initialized(span) => span.setStatus(status)
    }

  override def traceId: IO[Option[String]] =
    local.get.flatMap {
      case Empty(_) =>
        None.pure[IO]
      case Initialized(span) => span.context.traceId.show.some.pure[IO]
    }
}

object TracingContext {
  def create(entryPoint: EntryPoint[IO]): IO[TracingContext] =
    for {
      local <- IOLocal[ContextValue](Empty(entryPoint))
    } yield new TracingContext(local)

  sealed private trait ContextValue
  final private case class Initialized(span: Span[IO])       extends ContextValue
  final private case class Empty(entryPoint: EntryPoint[IO]) extends ContextValue
}
