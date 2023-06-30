package io.iohk.scevm.sidechain

import cats.effect.IO
import io.iohk.scevm.cardanofollower.plutus.TransactionRecipientDatum
import io.iohk.scevm.cardanofollower.testing.CardanoFollowerGenerators.mainchainTxHashGen
import io.iohk.scevm.domain.{Address, BlockContext, ObftHeader, Slot, Token}
import io.iohk.scevm.exec.utils.TestInMemoryWorldState
import io.iohk.scevm.exec.vm.{IOEvmCall, WorldType}
import io.iohk.scevm.sidechain.BridgeContract.{IncomingTransaction, IncomingTransactionId}
import io.iohk.scevm.sidechain.IncomingCrossChainTransactionsProviderImpl.{
  BridgeContract,
  MainchainTransactionProvider,
  MainchainUnstableTransactionProvider
}
import io.iohk.scevm.sidechain.PendingTransactionsService.PendingTransactionsResponse
import io.iohk.scevm.sidechain.testing.SidechainGenerators
import io.iohk.scevm.sidechain.{IncomingCrossChainTransaction, ValidIncomingCrossChainTransaction}
import io.iohk.scevm.testing.{BlockGenerators, Generators, IOSupport, NormalPatience}
import io.iohk.scevm.trustlesssidechain.cardano._
import io.janstenpickle.trace4cats.inject.Trace
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class IncomingCrossChainTransactionsProviderSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with IOSupport
    with NormalPatience {

  implicit val trace: Trace[IO] = Trace.Implicits.noop[IO]

  val slotGen: Gen[Slot]         = Generators.anySlotGen
  val headerGen: Gen[ObftHeader] = BlockGenerators.obftBlockHeaderGen
  val utxoIdGen: Gen[UtxoId]     = SidechainGenerators.utxoIdGen
  val world: WorldType           = TestInMemoryWorldState.worldStateGen.sample.get

  val recipientAddress: Address             = Address("0x56a960bc95fc5b8153ebe6a76c049b555d674593")
  val validDatum: TransactionRecipientDatum = TransactionRecipientDatum(recipientAddress)
  val utxoId1: MainchainTxHash =
    MainchainTxHash.decodeUnsafe("4e3a6e7fdcb0d0efa17bf79c13aed2b4cb9baf37fb1aa2e39553d5bd720c5c97")
  val utxoId2: MainchainTxHash =
    MainchainTxHash.decodeUnsafe("4e3a6e7fdcb0d0efa17bf79c13aed2b4cb9baf37fb1aa2e39553d5bd720c5c98")
  val utxoId3: MainchainTxHash =
    MainchainTxHash.decodeUnsafe("4e3a6e7fdcb0d0efa17bf79c13aed2b4cb9baf37fb1aa2e39553d5bd720c5c99")
  val validTransaction1: IncomingCrossChainTransaction =
    IncomingCrossChainTransaction(validDatum, Token(200), utxoId1, MainchainBlockNumber(38))
  val validTransaction2: IncomingCrossChainTransaction = validTransaction1.copy(value = Token(333), txId = utxoId2)
  val validTransaction3: IncomingCrossChainTransaction = validTransaction1.copy(value = Token(555), txId = utxoId3)

  val irrelevantAddress: Address = Address("0x000960bc95fc5b8153ebe6a76c049b555d674000")

  def mainchainSlotDerivation(expectedSlot: Slot): MainchainSlotDerivation = s =>
    if (s == expectedSlot) MainchainSlot(s.number.longValue)
    else throw new Exception(s"Expected $expectedSlot but got $s")

  def bridgeContract(
      result: Option[BridgeContract.IncomingTransactionId]
  ): BridgeContract[IOEvmCall] =
    new BridgeContract[IOEvmCall] {
      override def getLastProcessedIncomingTransaction: IOEvmCall[Option[IncomingTransactionId]] =
        IOEvmCall.pure(result)
    }

  def mainchainStableTxProvider(
      txs: List[IncomingCrossChainTransaction]
  ): MainchainTransactionProvider[IO] = (_, _) => IO.pure(txs)

  def mainchainUnstableTxProvider(
      txs: List[IncomingCrossChainTransaction]
  ): MainchainUnstableTransactionProvider[IO] = (_, _) => IO.pure(txs)

  def failingMainchainUnstableTxProvider: MainchainUnstableTransactionProvider[IO] =
    (_, _) => IO.raiseError(new Exception(s"Unexpected use of MainchainUnstableTransactionProvider"))

  "requests stable transaction since utxo obtained from bridge contract until given slot" in {
    forAll(headerGen, Gen.option(mainchainTxHashGen)) { case (header, utxoOpt) =>
      val incomingTransactionOpt =
        utxoOpt.map(utxo => IncomingTransaction(12345, irrelevantAddress, IncomingTransactionId.fromTxHash(utxo)))
      val incomingCrossChainTransactions = List(validTransaction1, validTransaction2)
      val provider = new IncomingCrossChainTransactionsProviderImpl[IO](
        bridgeContract(incomingTransactionOpt.map(_.txId)),
        mainchainStableTxProvider(
          incomingCrossChainTransactions
        ),
        failingMainchainUnstableTxProvider,
        mainchainSlotDerivation(header.slotNumber)
      )

      val result = provider.getTransactions(world.modifyBlockContext(_ => BlockContext.from(header)))

      result.ioValue shouldBe List(
        ValidIncomingCrossChainTransaction(recipientAddress, Token(200), utxoId1, MainchainBlockNumber(38)),
        ValidIncomingCrossChainTransaction(recipientAddress, Token(333), utxoId2, MainchainBlockNumber(38))
      )
    }
  }

  "combines unstable and stable transactions when requested for pending transactions" in {
    forAll(headerGen, Gen.option(mainchainTxHashGen)) { case (header, utxoOpt) =>
      val incomingTransactionOpt =
        utxoOpt.map(utxo => IncomingTransaction(12345, irrelevantAddress, IncomingTransactionId.fromTxHash(utxo)))
      val stableCrossChainTransactions   = List(validTransaction1, validTransaction2)
      val unstableCrossChainTransactions = List(validTransaction3)
      val provider = new IncomingCrossChainTransactionsProviderImpl[IO](
        bridgeContract(incomingTransactionOpt.map(_.txId)),
        mainchainStableTxProvider(stableCrossChainTransactions),
        mainchainUnstableTxProvider(unstableCrossChainTransactions),
        mainchainSlotDerivation(header.slotNumber)
      )

      val result = provider.getPendingTransactions(world.modifyBlockContext(_ => BlockContext.from(header)))

      result.ioValue shouldBe PendingTransactionsResponse(
        List(
          ValidIncomingCrossChainTransaction(recipientAddress, Token(200), utxoId1, MainchainBlockNumber(38)),
          ValidIncomingCrossChainTransaction(recipientAddress, Token(333), utxoId2, MainchainBlockNumber(38))
        ),
        List(
          ValidIncomingCrossChainTransaction(recipientAddress, Token(555), utxoId3, MainchainBlockNumber(38))
        )
      )
    }
  }

  "return unstable transactions when there are no unprocessed stable transactions" in {
    forAll(headerGen, Gen.option(mainchainTxHashGen)) { case (header, utxoOpt) =>
      val incomingTransactionOpt =
        utxoOpt.map(utxo => IncomingTransaction(12345, irrelevantAddress, IncomingTransactionId.fromTxHash(utxo)))
      val provider = new IncomingCrossChainTransactionsProviderImpl[IO](
        bridgeContract(incomingTransactionOpt.map(_.txId)),
        mainchainStableTxProvider(List.empty[IncomingCrossChainTransaction]),
        mainchainUnstableTxProvider(List(validTransaction2, validTransaction3)),
        mainchainSlotDerivation(header.slotNumber)
      )

      val result = provider.getPendingTransactions(world.modifyBlockContext(_ => BlockContext.from(header)))

      result.ioValue shouldBe PendingTransactionsResponse(
        List.empty,
        List(
          ValidIncomingCrossChainTransaction(recipientAddress, Token(333), utxoId2, MainchainBlockNumber(38)),
          ValidIncomingCrossChainTransaction(recipientAddress, Token(555), utxoId3, MainchainBlockNumber(38))
        )
      )
    }
  }

}
