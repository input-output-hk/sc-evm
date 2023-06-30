package io.iohk.scevm

import cats.Functor
import cats.effect.unsafe.implicits.global
import cats.effect.{Deferred, IO, Resource}
import cats.syntax.all._
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.TestingSidechainValidator.ExpectedResult
import io.iohk.scevm.cardanofollower.datasource.DatasourceConfig
import io.iohk.scevm.domain.{BlockNumber, Nonce, SignedTransaction, Slot, Tick}
import io.iohk.scevm.ledger.SlotDerivation
import io.iohk.scevm.node.{Builder, ScEvmNode}
import io.iohk.scevm.sidechain.SidechainBlockchainConfig
import io.iohk.scevm.sidechain.transactions.OutgoingTxRecipient
import io.iohk.scevm.storage.execution.SlotBasedMempool
import io.iohk.scevm.utils.{Logger, SystemTime}
import io.janstenpickle.trace4cats.inject.Trace
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.File

class TestingSidechainSpec extends AnyWordSpec with Matchers with Logger {
  "scenario1 should work correctly" in {
    test(
      "scenario1",
      ExpectedResult(
        sidechainEpochs = 4,
        mainchainEpochs = 4,
        customAssertions = Map(
          // It corresponds to the outgoing transaction.
          // It's not in block 1 (slot 1), because there are already some incoming transactions
          // And as the outgoing transaction asks for all the gas, it's rejected during block preparation.
          // There is not incoming transactions for slot 2, so the outgoing transaction should be included in the block 2
          BlockNumber(2) -> 1,
          // The following blocks have more transactions than expected,
          // because transactions were accumulated during slots for MainchainEpoch == 1,
          // as the committee size was two small, no block could be created.
          // As consequence, the 3 following blocks are filled with these transactions.
          BlockNumber(100) -> 121,
          BlockNumber(101) -> 121,
          BlockNumber(102) -> 52
        )
      )
    )
  }

  // scalastyle:off method.length
  private def test(scenario: String, expectedResult: ExpectedResult): Unit = {
    Thread.setDefaultUncaughtExceptionHandler((t, e) => log.error("Uncaught exception in thread: " + t, e))

    val scenarioFile   = new File(s"tooling/sidechainTest/src/it/resources/scenario/$scenario.conf")
    val scenarioConfig = ConfigFactory.parseFile(scenarioFile)
    val rawConfig      = ConfigFactory.load().withFallback(scenarioConfig).getConfig("sc-evm")
    log.info("Config: {}", rawConfig.root().render(ConfigRenderOptions.concise()))

    val nodeConfig = ScEvmConfig.fromConfig(rawConfig)

    val sidechainBlockchainConfig = nodeConfig.blockchainConfig match {
      case conf: SidechainBlockchainConfig => conf
      case _                               => throw new RuntimeException("This code should run in sidechain mode")
    }

    val mockConf = nodeConfig.extraSidechainConfig.datasource match {
      case conf: DatasourceConfig.Mock => conf
      case _                           => throw new RuntimeException("This code should run using a mock datasource")
    }

    (for {
      _                                     <- IO.unit
      _                                      = log.info(s"""Testing sidechain with mocked chain-follower!
           |- number of slots: ${mockConf.numberOfSlots}""".stripMargin)
      implicit0(systemTime: SystemTime[IO]) <- SystemTime.liveF[IO]
      slotDerivation: SlotDerivation = SlotDerivation
                                         .live(
                                           sidechainBlockchainConfig.genesisData.timestamp,
                                           nodeConfig.blockchainConfig.slotDuration
                                         )
                                         .fold(error => throw error, identity)

      endOfRunSignal <- Deferred[IO, Either[Throwable, Unit]]
      ticker =
        new TestingSidechainTicker(sidechainBlockchainConfig.genesisData.timestamp, mockConf.numberOfSlots)
          .ticker(nodeConfig.blockchainConfig.slotDuration)
          .onFinalize(endOfRunSignal.complete(Right(())).void)

      lockingTransactionAllocation = (
                                       Slot(1),
                                       LockingTransaction.create(
                                         nonce = Nonce(0),
                                         bridgeContractAddress = sidechainBlockchainConfig.bridgeContractAddress,
                                         transactionSigningKey = ECDSA.PrivateKey.fromHexUnsafe(
                                           "4a8c7a5e0441e535ab78ec4e6d401cf655d8b17942b377c68a80c749f8e3fa4b"
                                         ),
                                         outgoingTxRecipient = OutgoingTxRecipient.decodeUnsafe(
                                           "caadf8ad86e61a10f4b4e14d51acf1483dac59c9e90cbca5eda8b840"
                                         ),
                                         gasLimit = nodeConfig.blockchainConfig.genesisData.gasLimit
                                       )
                                     )

      _ <-
        createApp(nodeConfig, systemTime, slotDerivation, ticker, endOfRunSignal, lockingTransactionAllocation).use {
          case (endOfSlotEvents, runningApp, otherServices) =>
            endOfSlotEvents.get.flatMap {
              case Right(_) =>
                val validator =
                  TestingSidechainValidator(sidechainBlockchainConfig, mockConf, nodeConfig.blockchainConfig)(
                    runningApp,
                    otherServices,
                    lockingTransactionAllocation,
                    expectedResult
                  )
                log.info("Scenario executed! Running validation...").pure[IO] >> validator.validate()
              case Left(error) =>
                log.error(s"Testing sidechain stopped. Reason: ${error.getMessage}", error).pure[IO]
            }
        }
    } yield ())
      .unsafeRunSync()
  }
  // scalastyle:on method.length

  private def createApp(
      nodeConfig: ScEvmConfig,
      systemTime: SystemTime[IO],
      slotDerivation: SlotDerivation,
      ticker: fs2.Stream[IO, Tick],
      killSignal: Deferred[IO, Either[Throwable, Unit]],
      lockingTransactionAllocation: (Slot, SignedTransaction)
  ): Resource[IO, (Deferred[IO, Either[Throwable, Unit]], ScEvmNode[IO], Builder)] =
    for {
      _         <- Resource.unit
      trace      = Trace.Implicits.noop[IO]
      builder   <- ScEvm.createApp(nodeConfig, ticker, slotDerivation)(trace, systemTime)
      scEvmNode <- builder.startApp(killSignal)
      _ <- Resource.eval(
             sendLockingTransactionAtSlot(scEvmNode)(lockingTransactionAllocation._1, lockingTransactionAllocation._2)
           )
    } yield (killSignal, scEvmNode, builder)

  private def sendLockingTransactionAtSlot[F[_]: Functor](
      scEvmNode: ScEvmNode[F]
  )(slot: Slot, lockingTransaction: SignedTransaction): F[Unit] =
    scEvmNode.transactionMempool.add(lockingTransaction, slot).map {
      case SlotBasedMempool.Added(_, _)    => ()
      case SlotBasedMempool.NotAdded(_, _) => throw new RuntimeException("Transaction should be accepted by mempool")
    }

}
