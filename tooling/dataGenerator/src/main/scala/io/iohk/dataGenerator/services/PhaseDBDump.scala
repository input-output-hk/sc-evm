package io.iohk.dataGenerator.services

import cats.effect.IO
import cats.syntax.all._
import fs2.io.file.{CopyFlag, CopyFlags, Files, Path}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object PhaseDBDump {
  private val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger

  def dumpDbFiles(sourceDir: Path, destinationName: String): IO[Unit] = {
    val destinationDir = sourceDir.parent.get.resolve(destinationName)
    for {
      destinationExists <- Files[IO].exists(destinationDir)
      _                 <- logger.info(s"$destinationDir exists $destinationExists")
      _                 <- IO.whenA(!destinationExists)(Files[IO].createDirectories(destinationDir))
      _ <- Files[IO]
             .walk(sourceDir)
             .filterNot(_ == sourceDir)
             .evalTap { file =>
               val destination = destinationDir.resolve(file.fileName)
               logger.debug(s"Copying $file to $destination") >>
                 Files[IO].copy(file, destination, CopyFlags(CopyFlag.ReplaceExisting))
             }
             .evalTap(file => logger.debug(s"Copied $file"))
             .compile
             .drain
    } yield ()
  }
}
