package io.iohk.scevm

import cats.data.OptionT
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.TestingSidechainValidator.ExpectedResult
import io.iohk.scevm.cardanofollower.datasource.DatasourceConfig
import io.iohk.scevm.cardanofollower.datasource.mock.{IncomingTransactionGenerator, IncomingTransactionGeneratorImpl}
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.pos.ConsensusService
import io.iohk.scevm.consensus.pos.ConsensusService.BetterBranch
import io.iohk.scevm.domain.{Address, BlockNumber, EpochPhase, ObftBlock, SignedTransaction, Slot, TransactionOutcome}
import io.iohk.scevm.ledger.BlockImportService.ImportedBlock
import io.iohk.scevm.node.{Builder, ScEvmNode}
import io.iohk.scevm.sidechain._
import io.iohk.scevm.trustlesssidechain.cardano.MainchainEpoch
import io.iohk.scevm.utils.{Logger, SystemTime}

class TestingSidechainValidator private (
    app: ScEvmNode[IO],
    otherServices: Builder,
    lockingTransactionAllocation: (Slot, SignedTransaction),
    sidechainEpochDerivation: SidechainEpochDerivation[IO],
    mainchainEpochDerivation: MainchainEpochDerivation,
    mainchainSlotDerivation: MainchainSlotDerivation,
    incomingTransactionGenerator: IncomingTransactionGenerator,
    expectedResult: ExpectedResult
) extends Logger {

  // scalastyle:off method.length
  def validate(): IO[Unit] = {
    val blocksReader         = app.storage.blockchainStorage
    val currentBranchService = app.currentBranchService
    val consensusService     = otherServices.consensusBuilder
    val genesisHeader        = otherServices.genesisHeader
    val sidechainModule = otherServices.chainModule match {
      case module: SidechainModule[IO, _] => module
      case _                              => throw new RuntimeException("This code should run in sidechain mode")
    }

    for {
      bestBlock <-
        OptionT
          .liftF(currentBranchService.signal.get.map(_.bestHash))
          .flatMap(hash => OptionT(blocksReader.getBlock(hash)))
          .getOrRaise(new RuntimeException("Could not find best block"))
      _ = log.info(show"Best block $bestBlock")

      // Validate progression
      sidechainEpoch = sidechainEpochDerivation.getSidechainEpoch(bestBlock.header.slotNumber)
      mainchainEpoch = mainchainEpochDerivation.getMainchainEpoch(sidechainEpoch)

      _ = assert(
            sidechainEpoch.number == expectedResult.sidechainEpochs,
            s"Expected ${expectedResult.sidechainEpochs} sidechain epochs, but got ${sidechainEpoch.number}"
          )
      _ = assert(
            mainchainEpoch.number == expectedResult.mainchainEpochs,
            s"Expected ${expectedResult.mainchainEpochs} mainchain epochs, but got ${mainchainEpoch.number}"
          )

      _ <- {
        val sidechainEpochWithOutgoingTransactions =
          sidechainEpochDerivation.getSidechainEpoch(lockingTransactionAllocation._1)
        sidechainModule
          .getOutgoingTransaction(sidechainEpochWithOutgoingTransactions)
          .map(outgoingTransactions =>
            assert(
              outgoingTransactions.nonEmpty,
              show"There should be outgoing transactions in $sidechainEpochWithOutgoingTransactions"
            )
          )
      }

      _ <- bestBlock.hash
             .tailRecM[IO, Unit] { hash =>
               blocksReader.getBlock(hash).flatMap {
                 case Some(block) => checkBlock(block).as(Left(block.header.parentHash))
                 case None        => IO.pure(Right(()))
               }
             }

      // Reset current branch and re-import all blocks
      genesis <- OptionT(blocksReader.getBlock(genesisHeader.hash))
                   .getOrRaise(new RuntimeException("Could not find genesis"))
      _ <- currentBranchService.newBranch(BetterBranch(Vector(genesis), genesis.header))
      consensusResult <- consensusService.resolve(
                           ImportedBlock(bestBlock, fullyValidated = false),
                           currentBranchService.signal.get.unsafeRunSync()
                         )

      // Check consensus
      _ <- consensusResult match {
             case ConsensusService.ConnectedBranch(_, Some(_)) => IO.unit
             case ConsensusService.ConnectedBranch(_, _) =>
               IO.raiseError(new RuntimeException("Error: should be a better branch"))
             case ConsensusService.DisconnectedBranchMissingParent(_, _) =>
               IO.raiseError(new RuntimeException("Error: missing parent"))
             case ConsensusService.ForkedBeyondStableBranch(_) =>
               IO.raiseError(new RuntimeException("Error: forked"))
           }
    } yield ()
  }
  // scalastyle:on method.length

  private def checkBlock(block: ObftBlock): IO[Unit] =
    for {
      _            <- checkReceipt(block)
      transactions <- checkTransactions(block).pure[IO]
    } yield transactions

  private def checkReceipt(block: ObftBlock): IO[Unit] =
    app.storage.receiptStorage.getReceiptsByHash(block.header.hash).map {
      case Some(receipts) =>
        receipts.foreach(receipt =>
          assert(
            receipt.postTransactionStateHash == TransactionOutcome.SuccessOutcome,
            show"Receipt $receipt is not successful"
          )
        )
      case None => throw new RuntimeException("Expected all blocks to have a receipt")
    }

  // scalastyle:off method.length
  private def checkTransactions(block: ObftBlock): Unit = {
    val sidechainEpoch = sidechainEpochDerivation.getSidechainEpoch(block.header.slotNumber)
    val mainchainEpoch = mainchainEpochDerivation.getMainchainEpoch(sidechainEpoch)
    val mainchainSlot  = mainchainSlotDerivation.getMainchainSlot(block.header.slotNumber)
    assert(
      mainchainEpoch != MainchainEpoch(1),
      "Mainchain epoch 1 should not appear because there is not enough candidate"
    )

    if (block.number != BlockNumber(0)) {
      val phase = sidechainEpochDerivation.getSidechainEpochPhase(block.header.slotNumber)

      val nbIncomingTransactions = incomingTransactionGenerator.getNumberOfTransactions(mainchainSlot)

      val expectedNumberOfTransactions = expectedResult.customAssertions.get(block.number) match {
        case Some(expectedNbTxs) => expectedNbTxs
        case None =>
          if (phase == EpochPhase.Handover) {
            // An additional handover signature transaction must be present
            nbIncomingTransactions + 1
          } else {
            nbIncomingTransactions
          }
      }

      assert(
        block.body.transactionList.length == expectedNumberOfTransactions,
        show"Expected $expectedNumberOfTransactions, but got ${block.body.transactionList.length} for block $block during phase $phase"
      )
    }
  }
  // scalastyle:on method.length

}

object TestingSidechainValidator extends Logger {

  final case class ExpectedResult(
      sidechainEpochs: Int,
      mainchainEpochs: Int,
      customAssertions: Map[BlockNumber, Int]
  )

  def apply(
      sidechainBlockchainConfig: SidechainBlockchainConfig,
      mockConfig: DatasourceConfig.Mock,
      blockchainConfig: BlockchainConfig
  )(
      app: ScEvmNode[IO],
      otherServices: Builder,
      lockingTransactionAllocation: (Slot, SignedTransaction),
      expectedResult: ExpectedResult
  )(implicit systemTime: SystemTime[IO]): TestingSidechainValidator = {

    val sidechainEpochDerivation = new SidechainEpochDerivationImpl[IO](sidechainBlockchainConfig.obftConfig)
    val mainchainEpochDerivation =
      new MainchainEpochDerivationImpl(
        sidechainBlockchainConfig.obftConfig,
        sidechainBlockchainConfig.mainchainConfig
      )
    val mainchainSlotDerivation =
      new MainchainSlotDerivationImpl(
        sidechainBlockchainConfig.mainchainConfig,
        sidechainBlockchainConfig.obftConfig.slotDuration
      )

    val simulationDuration = mockConfig.numberOfSlots * blockchainConfig.slotDuration
    val numberOfMainchainSlot =
      (simulationDuration / sidechainBlockchainConfig.mainchainConfig.slotDuration).toInt

    val incomingTransactionGenerator = IncomingTransactionGeneratorImpl(
      numberOfMainchainSlot,
      mockConfig,
      mockConfig.candidates.map { candidate =>
        val sidechainPubKey = ECDSA.PublicKey.fromPrivateKey(candidate.sidechainPrvKey)
        Address.fromPublicKey(sidechainPubKey)
      }
    )

    new TestingSidechainValidator(
      app,
      otherServices,
      lockingTransactionAllocation,
      sidechainEpochDerivation,
      mainchainEpochDerivation,
      mainchainSlotDerivation,
      incomingTransactionGenerator,
      expectedResult
    )
  }
}
