package io.iohk.scevm.extvm

import cats.effect.{IO, Resource}
import cats.syntax.all._
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.exec.vm.{EVM, Storage, VM, WorldState}
import io.iohk.scevm.extvm.VmConfig.{IELEConfig, InternalEVMConfig, KEVMConfig, TestModeConfig}
import io.iohk.scevm.extvm.iele.IELEClient
import io.iohk.scevm.extvm.kevm.KEVMClient
import io.iohk.scevm.extvm.testevm.VmServerApp
import org.typelevel.log4cats.slf4j.Slf4jLogger

object VMSetup {

  def vm[W <: WorldState[W, S], S <: Storage[S]](
      vmConfig: VmConfig,
      blockchainConfig: BlockchainConfig
  ): Resource[IO, VM[IO, W, S]] =
    vmConfig match {
      case InternalEVMConfig() =>
        log("Starting internal VM") >> Resource.eval(EVM[W, S]().pure[IO])
      case config: KEVMConfig =>
        log(s"Connecting to KEVM at ${config.host}:${config.port}") >>
          KEVMClient.start[W, S](config, blockchainConfig)
      case config: IELEConfig =>
        log(s"Connecting to IELE at ${config.host}:${config.port}") >>
          IELEClient.start[W, S](config)
      case TestModeConfig(host, port) =>
        log(s"Starting internal EVM as a external VM server at $host:$port.") >>
          startTestServer(host, port) >> KEVMClient.start[W, S](
            KEVMConfig(host, port, ""),
            blockchainConfig
          )
    }

  private def startTestServer(host: String, port: Int): Resource[IO, Unit] =
    Resource.eval(IO(VmServerApp.main(Array(host, port.toString))))

  private def log(msg: String): Resource[IO, Unit] = Resource.eval(Slf4jLogger.getLogger[IO].info(msg))
}
