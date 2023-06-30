package io.iohk.scevm.observer

import cats.data.OptionT
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._
import com.typesafe.config.ConfigFactory
import doobie.syntax.all._
import doobie.util.transactor
import io.iohk.scevm.Instrumentation
import io.iohk.scevm.domain.Address
import io.iohk.scevm.observer.Db
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import sttp.client3.httpclient.fs2.HttpClientFs2Backend

import scala.concurrent.duration._

object Observer extends IOApp {

  private val ConversionRate = BigInt(10).pow(9)

  implicit private val logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  override def run(args: List[String]): IO[ExitCode] = {
    val rawConfig = ConfigFactory.load().getConfig("observer")
    val config    = ObserverConfig.parse(rawConfig)
    (for {
      xa         <- new Db(config.dbSync).transactor[IO]
      _          <- Instrumentation.startMetricsServer[IO](config.metrics)
      metrics    <- PrometheusExternalMetrics.apply[IO]
      backend    <- HttpClientFs2Backend.resource[IO]()
      scEvmClient = new ScEvmRpcClient[IO](config.scEvmUrl, backend)
    } yield (metrics, scEvmClient, xa))
      .use { case (metrics, scEvmClient, xa) =>
        updateMetrics(metrics, scEvmClient, config.interval, xa, config.fuel, config.step, config.bridgeContract)
      }
      .as(ExitCode.Success)
  }

  private def updateMetrics(
      metrics: ExternalMetrics[IO],
      client: ScEvmRpcClient[IO],
      interval: FiniteDuration,
      xa: transactor.Transactor[IO],
      fuel: Asset,
      step: Int,
      bridgeContract: Address
  ) =
    (fs2.Stream.emit(()) ++
      fs2.Stream
        .awakeEvery[IO](interval)
        .void)
      .evalScan(FuelTokensCountingState(0, 0, 0, 0, 0)) { case (currentState, _) =>
        client.getStatus.attempt
          .flatMap {
            case Right(scEvmStatus) =>
              (for {
                _ <- Logger[IO].info(s"SC EVM status: $scEvmStatus")
                newState <-
                  mainchainMetrics(currentState, scEvmStatus.mainchain.bestBlock.number, fuel, xa, metrics, step)
                _ <- bridgeContractMetrics(metrics, client, scEvmStatus.sidechain, bridgeContract)
              } yield newState)
            case Left(error) =>
              Logger[IO].error(error)("Error occurred when getting status from SC EVM").as(currentState)
          }
      }
      .compile
      .drain

  private def bridgeContractMetrics(
      metrics: ExternalMetrics[IO],
      client: ScEvmRpcClient[IO],
      status: ScEvmRpcClient.SidechainData,
      bridgeContract: Address
  ) = (for {
    _              <- Logger[IO].info("Updating bridge contract metrics")
    stableBalance  <- client.getBalance(bridgeContract, status.stableBlock.number)
    pendingBalance <- client.getBalance(bridgeContract, status.bestBlock.number)
    _              <- metrics.totalValueLockedStable.set((stableBalance / ConversionRate).toDouble)
    _              <- metrics.totalValueLockedPending.set((pendingBalance / ConversionRate).toDouble)
  } yield ()).attempt.flatTap {
    case Left(error) => Logger[IO].error(error)("Error occured when computing bridge metrics")
    case Right(_)    => Logger[IO].info("Bridge contract metrics updated")
  }

  private def countFuelTokens(
      startingState: FuelTokensCountingState,
      targetBlock: Long,
      fuel: Asset,
      xa: transactor.Transactor[IO],
      metrics: ExternalMetrics[IO],
      step: Int
  ): IO[Option[FuelTokensCountingState]] = {
    def go(currentState: FuelTokensCountingState): IO[Option[FuelTokensCountingState]] = {
      val lowerBoundary = currentState.scanRangeLowerBoundary
      val upperBoundary = Math.min(lowerBoundary + step, targetBlock)
      Logger[IO].info(s"Catching up with db-sync state. Current block is ${lowerBoundary}") >>
        (for {
          (burnAmount, burnCount) <- DbSyncRepository.sumTotalBurnedAmount(fuel, lowerBoundary, upperBoundary)
          (mintAmount, mintCount) <- DbSyncRepository.sumTotalMintedAmount(fuel, lowerBoundary, upperBoundary)
        } yield FuelTokensCountingState(
          currentState.burnAmount + burnAmount,
          currentState.burnCount + burnCount,
          currentState.mintAmount + mintAmount,
          currentState.mintCount + mintCount,
          scanRangeLowerBoundary = upperBoundary
        ))
          .transact(xa)
          .attempt
          .flatMap {
            case Left(error) =>
              Logger[IO]
                .error(error)("Error occured when computing mainchain metrics")
                .as(none[FuelTokensCountingState])
            case Right(newState) =>
              if (newState.scanRangeLowerBoundary < targetBlock) {
                Logger[IO].info(s"Intermediate state: $newState") >>
                  go(newState)
              } else {
                IO.pure(newState.some)
              }
          }
    }
    go(startingState)
  }

  private def mainchainMetrics(
      currentState: FuelTokensCountingState,
      targetBlock: Long,
      fuel: Asset,
      xa: transactor.Transactor[IO],
      metrics: ExternalMetrics[IO],
      step: Int
  ) =
    OptionT(countFuelTokens(currentState, targetBlock, fuel, xa, metrics, step))
      .semiflatMap { newState =>
        (for {
          _ <- metrics.burnTransactionsCount.set(newState.burnCount.toDouble)
          _ <- metrics.totalValueBurned.set(newState.burnAmount.toDouble)
          _ <- metrics.mintTransactionsCount.set(newState.mintCount.toDouble)
          _ <- metrics.totalValueMinted.set(newState.mintAmount.toDouble)
          _ <- Logger[IO].info(s"Mainchain metrics updated: $newState")
        } yield newState)
      }
      .getOrElse(currentState)

  final case class FuelTokensCountingState(
      burnAmount: BigInt,
      burnCount: BigInt,
      mintAmount: BigInt,
      mintCount: BigInt,
      scanRangeLowerBoundary: Long // inclusive
  )
}
