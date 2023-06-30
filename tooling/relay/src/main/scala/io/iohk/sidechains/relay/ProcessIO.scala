package io.iohk.sidechains.relay

import cats.effect.IO
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.sys.process._

/** Responsibility execute process in IO with logging added. */
object ProcessIO {

  implicit val log: Logger[IO] = Slf4jLogger.getLogger[IO]

  def execute(command: List[String], timeout: FiniteDuration = FiniteDuration(1, TimeUnit.MINUTES)): IO[String] =
    log.info(s"Executing: ${command.mkString(" ")}") *>
      IO(command.!!).timeoutAndForget(timeout).attempt.flatMap {
        case Left(err) =>
          log.error(s"Execution failed, output: $err") *> IO.raiseError(new Exception(s"Command failed: $err"))
        case Right(output) =>
          log.info(s"Execution success, output: $output").as(output)
      }
}
