package io.iohk.scevm.config

import cats.effect.{Resource, Sync}
import io.iohk.scevm.domain.ObftGenesisData

import scala.io.Source

object GenesisDataFileLoader {

  def loadGenesisDataFile[F[_]](
      customGenesisFile: String
  )(implicit F: Sync[F]): F[ObftGenesisData] = {
    val maybeJsonString = F
      .attemptT(fromFile(customGenesisFile))
      .orElse(F.attemptT(fromResource(customGenesisFile)))
      .value

    F.flatMap(maybeJsonString) {
      case Left(e)           => F.raiseError(e)
      case Right(jsonString) => F.delay(GenesisDataJson.fromJsonString(jsonString))
    }
  }

  private def fromSource[F[_]](source: => Source)(implicit F: Sync[F]): F[String] =
    Resource.fromAutoCloseable(F.delay(source)).use(s => F.delay(s.getLines().mkString))

  private def fromFile[F[_]: Sync](fileName: String): F[String]         = fromSource(Source.fromFile(fileName))
  private def fromResource[F[_]: Sync](resourceName: String): F[String] = fromSource(Source.fromResource(resourceName))
}
