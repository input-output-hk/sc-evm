package io.iohk.scevm

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import fs2.concurrent.Topic
import io.iohk.scevm.domain.Tick
import io.iohk.scevm.rpc.http.JsonRpcHttpServer
import io.iohk.scevm.testnode.{ReverseProxyJsonRpcRouter, TestNodeConfig}
import io.iohk.scevm.utils.Logger

object TestNodeScEvm extends Logger {
  def main(args: Array[String]): Unit = {
    val rawConfig      = ConfigFactory.load().getConfig("sc-evm")
    val nodeConfig     = ScEvmConfig.fromConfig(rawConfig)
    val testNodeConfig = TestNodeConfig.fromConfig(rawConfig)

    log.info("Starting SC EVM in test mode")
    log.info("Client version: {}", nodeConfig.appConfig.clientVersion)
    log.info("Network: {}", nodeConfig.networkConfig)
    log.info("Config: {}", rawConfig.root().render(ConfigRenderOptions.concise()))

    val ticksTopic = Topic[IO, Tick].unsafeRunSync()
    val controller = new ReverseProxyJsonRpcRouter(testNodeConfig, nodeConfig, ticksTopic)

    val server = JsonRpcHttpServer[IO](
      () => controller.appRoutes,
      nodeConfig.jsonRpcHttpServerConfig
    )

    server.flatMap(_.run()).useForever.unsafeRunSync()
  }
}
