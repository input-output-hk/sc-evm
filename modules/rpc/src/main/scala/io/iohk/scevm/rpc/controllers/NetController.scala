package io.iohk.scevm.rpc.controllers

import cats.Show
import cats.effect.IO
import io.estatico.newtype.macros.newtype
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.rpc.ServiceResponse
import io.iohk.scevm.rpc.controllers.NetController.ChainMode
import io.iohk.scevm.rpc.dispatchers.EmptyRequest
import io.iohk.scevm.serialization.Newtype

import scala.annotation.unused
import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions

object NetController {
  sealed trait ChainMode
  object ChainMode {
    final case object Standalone extends ChainMode
    final case object Sidechain  extends ChainMode
  }

  final case class GetNetworkInfoResponse(
      chainId: ChainId,
      networkId: Int,
      stabilityParameter: Int,
      slotDuration: FiniteDuration,
      chainMode: ChainMode
  )

  @newtype final case class NetVersion(value: String)

  object NetVersion {
    implicit val show: Show[NetVersion] = Show.show(t => t.value)

    implicit val valueClass: Newtype[NetVersion, String] =
      Newtype[NetVersion, String](NetVersion.apply, _.value)

    // Manifest for @newtype classes - required for now, as it's required by json4s (which is used by Armadillo)
    implicit val manifest: Manifest[NetVersion] = new Manifest[NetVersion] {
      override def runtimeClass: Class[_] = NetVersion.getClass
    }
  }
}

class NetController(blockchainConfig: BlockchainConfig, chainMode: ChainMode) {
  import NetController._

  def isListening(): IO[Boolean] = IO.pure(true)

  def version(): IO[NetVersion] = IO.pure(NetVersion(blockchainConfig.networkId.toString))

  def getNetworkInfo(@unused req: EmptyRequest): ServiceResponse[GetNetworkInfoResponse] =
    IO.pure(
      Right(
        GetNetworkInfoResponse(
          blockchainConfig.chainId,
          blockchainConfig.networkId,
          blockchainConfig.stabilityParameter,
          blockchainConfig.slotDuration,
          chainMode
        )
      )
    )
}
