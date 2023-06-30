package io.iohk.scevm.utils

import cats.implicits.toShow
import cats.{Applicative, Show}
import org.typelevel.log4cats.Logger

object Log4sOps {
  implicit class EitherLog4sOps[F[_]](val logger: Logger[F]) extends AnyVal {
    def logLeftAsError[A: Show](either: Either[A, _])(implicit app: Applicative[F]): F[Unit] = either match {
      case Left(value) => logger.error(value.show)
      case Right(_)    => app.unit
    }
  }

}
