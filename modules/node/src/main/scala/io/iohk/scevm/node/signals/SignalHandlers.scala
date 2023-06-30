package io.iohk.scevm.node.signals

import cats.effect.std.Dispatcher
import cats.effect.{Async, Resource}
import cats.implicits._
import io.iohk.scevm.utils.Logger
import org.typelevel.log4cats.LoggerFactory
import sun.misc.Signal

import java.nio.file.Path
import scala.util.{Failure, Success, Try}

object SignalHandlers extends Logger {

  private val BACKUP_SIGNAL = "USR2"
  def setupBackupSignal[F[_]: Async: LoggerFactory](
      backupStorage: () => F[Try[Either[String, Path]]]
  ): Resource[F, Unit] = {
    val log = LoggerFactory[F].getLogger
    for {
      dispatcher <- Dispatcher.sequential[F]
      _ <- Resource.eval {
             val backupAction = backupStorage().flatMap {
               case Success(Left(error)) => log.error(s"Could not backup the database: $error")
               case Success(Right(path)) => log.info(s"Created a backup of the database in $path")
               case Failure(e)           => log.error(e)("Error while creating the backup")
             }
             Async[F].delay(
               Signal.handle(
                 new Signal(BACKUP_SIGNAL),
                 _ => dispatcher.unsafeRunSync(backupAction)
               )
             )
           }
    } yield ()
  }

}
