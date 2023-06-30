package io.iohk.dataGenerator.scenarios

import cats.effect.{ExitCode, IO, IOApp, Resource}
import com.typesafe.config.ConfigFactory
import io.iohk.dataGenerator.domain.Validator
import io.iohk.dataGenerator.services.ChainGenerator
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

object SimpleForkGenerator extends IOApp {
  // scalastyle:off
  override def run(args: List[String]): IO[ExitCode] = {
    implicit val logging: LoggerFactory[IO] = Slf4jFactory[IO]

    // Run
    val init = for {
      _                    <- Resource.unit
      scEvmConfig           = ConfigFactory.parseResources("simple-fork.conf").resolve().getConfig("sc-evm")
      validators            = Validator.fromConfig(scEvmConfig)
      (generator, genesis) <- ChainGenerator.build(scEvmConfig, validators)
    } yield (generator, genesis, validators)

    init.use { case (generator, genesis, validators) =>
      for {
        // Base chain
        _ <- IO.unit
        tipChain1 <- generator.run(
                       "chain-1",
                       startingBlock = genesis,
                       length = 50,
                       participating = validators
                     )
        // Fork 1
        tipFork1 <- generator.run(
                      "fork-1",
                      startingBlock = tipChain1,
                      length = 5,
                      participating = validators.tail
                    )
        // Fork 2
        tipFork2 <- generator.run(
                      "fork-2",
                      startingBlock = tipChain1,
                      length = 25,
                      participating = validators.tail.tail
                    )
      } yield ExitCode.Success
    }
  }
}
