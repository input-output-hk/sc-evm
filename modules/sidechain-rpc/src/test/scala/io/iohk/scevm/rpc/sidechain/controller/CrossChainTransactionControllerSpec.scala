package io.iohk.scevm.rpc.sidechain.controller

import cats.Applicative
import cats.effect.IO
import cats.syntax.all._
import io.iohk.scevm.rpc.sidechain.CrossChainTransactionController.{
  GetOutgoingTransactionsResponse,
  OutgoingTransactionsService
}
import io.iohk.scevm.rpc.sidechain.SidechainController.GetPendingTransactionsResponse
import io.iohk.scevm.rpc.sidechain.controller.CrossChainTransactionControllerSpec._
import io.iohk.scevm.rpc.sidechain.{CrossChainTransactionController, SidechainEpochParam}
import io.iohk.scevm.sidechain.PendingTransactionsService.PendingTransactionsResponse
import io.iohk.scevm.sidechain.SidechainFixtures.{MainchainEpochDerivationStub, SidechainEpochDerivationStub}
import io.iohk.scevm.sidechain.testing.SidechainGenerators
import io.iohk.scevm.sidechain.transactions.OutgoingTransaction
import io.iohk.scevm.sidechain.{
  MainchainEpochDerivation,
  PendingTransactionsService,
  SidechainEpoch,
  SidechainEpochDerivation
}
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalacheck.Gen
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

class CrossChainTransactionControllerSpec
    extends AnyWordSpec
    with Matchers
    with EitherValues
    with IOSupport
    with ScalaFutures
    with ScalaCheckDrivenPropertyChecks
    with NormalPatience {

  "should return pending incoming transactions" in {
    val gen             = Gen.listOf(SidechainGenerators.validIncomingCrossChainTransactionGen)
    val stable          = gen.sample.get
    val unstable        = gen.sample.get
    val serviceResponse = PendingTransactionsResponse(stable, unstable)
    val controller = createCrossChainTxController(
      pendingTransactionsService = PendingTransactionServicesStub[IO](serviceResponse)
    )
    controller.getPendingTransactions().ioValue.value shouldBe GetPendingTransactionsResponse(
      pending = unstable,
      queued = stable
    )
  }

  "should return outgoing transactions" in {
    val txs = Gen.listOf(SidechainGenerators.outgoingTransactionGen).sample.get
    val controller = createCrossChainTxController(
      outgoingTransactionsService = new OutgoingTransactionsServiceStub[IO](txs)
    )
    val result = controller
      .getOutgoingTransactions(SidechainEpochParam.Latest)
      .ioValue
      .value

    result shouldBe GetOutgoingTransactionsResponse(txs.map { tx =>
      OutgoingTransaction(tx.value, tx.recipient, tx.txIndex)
    })
  }
}

object CrossChainTransactionControllerSpec {

  def createCrossChainTxController(
      sidechainEpochDerivation: SidechainEpochDerivation[IO] = SidechainEpochDerivationStub[IO](),
      mainchainEpochDerivation: MainchainEpochDerivation = MainchainEpochDerivationStub(),
      pendingTransactionsService: PendingTransactionsService[IO] =
        PendingTransactionServicesStub[IO](PendingTransactionsResponse(List.empty, List.empty)),
      outgoingTransactionsService: OutgoingTransactionsService[IO] = new OutgoingTransactionsServiceStub[IO](Seq.empty)
  ): CrossChainTransactionController[IO] =
    new CrossChainTransactionController[IO](
      SidechainControllerHelper.epochCalculator(sidechainEpochDerivation, mainchainEpochDerivation),
      pendingTransactionsService,
      outgoingTransactionsService
    )

  class OutgoingTransactionsServiceStub[F[_]: Applicative](response: Seq[OutgoingTransaction])
      extends OutgoingTransactionsService[F] {
    override def getOutgoingTransactions(epoch: SidechainEpoch): F[Seq[OutgoingTransaction]] =
      response.pure[F]
  }

}
