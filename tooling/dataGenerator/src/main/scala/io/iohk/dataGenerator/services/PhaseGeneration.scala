package io.iohk.dataGenerator.services

import cats.effect.{IO, Resource}
import cats.syntax.all._
import io.iohk.dataGenerator.domain.Validator
import io.iohk.dataGenerator.scenarios.ValuesForTransactions
import io.iohk.dataGenerator.services.transactions.TransactionGenerator
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.consensus.pos.BlockPreExecution
import io.iohk.scevm.db.storage.ReceiptStorage
import io.iohk.scevm.domain.{ObftBlock, ObftHeader, SignedTransaction, Slot}
import io.iohk.scevm.ledger.BlockRewarder.BlockRewarder
import io.iohk.scevm.ledger.blockgeneration.BlockGenerator
import io.iohk.scevm.ledger.{BlockPreparationImpl, LeaderElection, TransactionExecution}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.FiniteDuration

class PhaseGeneration private[dataGenerator] (
    blockGenerator: BlockGenerator[IO],
    leaderElection: LeaderElection[IO],
    transactionGenerator: TransactionGenerator
)(slotDuration: FiniteDuration) {
  private val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger

  def run(startingBlock: ObftBlock, length: Int, participating: IndexedSeq[Validator]): fs2.Stream[IO, ObftBlock] = {
    val initialSlot = startingBlock.header.slotNumber.add(1)
    fs2.Stream
      .iterateEval((startingBlock, initialSlot)) { case (parent, currentSlot) =>
        for {
          pubKeyOfSlot <- leaderElection.getSlotLeader(currentSlot).map(_.toOption.get)
          currentBlockOpt <- participating
                               .find(_.publicKey == pubKeyOfSlot)
                               .traverse { validatorOfSlot =>
                                 for {
                                   transactions <- transactionGenerator.transactions(
                                                     parent.header,
                                                     ValuesForTransactions.contracts,
                                                     ValuesForTransactions.senders
                                                   )
                                   _ <-
                                     logger.debug(
                                       show"Generated ${transactions.size} transactions for $currentSlot, parent=${parent.number}"
                                     )
                                   block <- generateBlockForSlot(
                                              currentSlot,
                                              length,
                                              parent,
                                              startingBlock.header,
                                              validatorOfSlot,
                                              transactions
                                            )
                                 } yield block
                               }
          _          <- logger.debug(show"Created $currentBlockOpt")
          latestBlock = currentBlockOpt.getOrElse(parent)
          nextSlot    = currentSlot.add(1)
        } yield (latestBlock, nextSlot)
      }
      .filterNot(_._1 == startingBlock)
      .collectWhile { case (block, slot) if slot <= initialSlot.add(length) => block }
      .changesBy(_.number.value)
  }

  private def generateBlockForSlot(
      currentSlot: Slot,
      length: Int,
      parent: ObftBlock,
      startingBlock: ObftHeader,
      validator: Validator,
      transactions: Seq[SignedTransaction]
  ): IO[ObftBlock] =
    for {
      blockForSlot <- blockGenerator
                        .generateBlock(
                          parent = parent.header,
                          transactionList = transactions,
                          slotNumber = currentSlot,
                          timestamp = {
                            val slotDistance = (currentSlot.number - parent.header.slotNumber.number).toInt
                            parent.header.unixTimestamp.add(slotDuration * slotDistance)
                          },
                          validatorPubKey = validator.publicKey,
                          validatorPrvKey = validator.privateKey
                        )

      _ <- {
        val total = startingBlock.slotNumber.add(length).number
        logger.debug(
          show"Progress: slot=${currentSlot.number}/$total, validator=${validator.publicKey.show}, block=$blockForSlot"
        )
      }
    } yield blockForSlot

}

object PhaseGeneration {

  def build(blockchainConfig: BlockchainConfig)(
      leaderElection: LeaderElection[IO],
      blockRewarder: BlockRewarder[IO],
      receiptStorage: ReceiptStorage[IO],
      transactionExecution: TransactionExecution[IO],
      transactionGenerator: TransactionGenerator,
      worldStateBuilder: WorldStateBuilder[IO]
  ): Resource[IO, PhaseGeneration] = {
    val blockPreparation =
      new BlockPreparationImpl[IO](
        BlockPreExecution.noop,
        transactionExecution,
        blockRewarder,
        worldStateBuilder
      )
    val blockGenerator =
      new BlockGeneratorWithPersistence(blockPreparation, receiptStorage)

    Resource.pure(
      new PhaseGeneration(
        blockGenerator,
        leaderElection,
        transactionGenerator
      )(blockchainConfig.slotDuration)
    )
  }
}
