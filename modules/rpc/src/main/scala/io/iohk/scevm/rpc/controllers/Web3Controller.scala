package io.iohk.scevm.rpc.controllers

import cats.Show
import cats.effect.IO
import io.estatico.newtype.macros.newtype
import io.iohk.scevm.config.AppConfig
import io.iohk.scevm.rpc.controllers.Web3Controller.ClientVersion
import io.iohk.scevm.serialization.Newtype

class Web3Controller(appConfig: AppConfig) {
  def clientVersion(): IO[ClientVersion] = IO(ClientVersion(appConfig.clientVersion))
}

object Web3Controller {

  import scala.language.implicitConversions

  @newtype final case class ClientVersion(value: String)

  object ClientVersion {
    implicit val show: Show[ClientVersion] = Show.show(t => t.value)

    implicit val valueClass: Newtype[ClientVersion, String] =
      Newtype[ClientVersion, String](ClientVersion.apply, _.value)

    // Manifest for @newtype classes - required for now, as it's required by json4s (which is used by Armadillo)
    implicit val manifest: Manifest[ClientVersion] = new Manifest[ClientVersion] {
      override def runtimeClass: Class[_] = ClientVersion.getClass
    }
  }
}
