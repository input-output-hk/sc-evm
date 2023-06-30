package io.iohk.scevm.rpc.controllers

import cats.effect.{IO, Resource}
import fs2.concurrent.Signal
import io.iohk.bytes.ByteString
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.db.dataSource.DataSource
import io.iohk.scevm.db.storage._
import io.iohk.scevm.domain.{Address, BlockContext, BlockHash, BlockNumber, LegacyTransaction, Nonce, SignedTransaction}
import io.iohk.scevm.exec.vm.InMemoryWorldState
import io.iohk.scevm.network.SlotBasedMempoolStub
import io.iohk.scevm.rpc.domain.RpcLegacyTransactionResponse
import io.iohk.scevm.testing.CryptoGenerators.ecdsaPrivateKeyGen
import io.iohk.scevm.testing.{IOSupport, NormalPatience, TestCoreConfigs, fixtures}
import org.scalacheck.Gen
import org.scalamock.scalatest.AsyncMockFactory
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.FixtureAsyncWordSpec

import scala.collection.mutable.ListBuffer

// scalastyle:off file.size.limit
class TxPoolControllerSpec
    extends FixtureAsyncWordSpec
    with Matchers
    with AsyncDataSourceFixture
    with BlocksFixture
    with AsyncMockFactory
    with EitherValues
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  val (key1, key2)            = Gen.zip(ecdsaPrivateKeyGen, ecdsaPrivateKeyGen).retryUntil { case (x, y) => x != y }.sample.get
  val tx1: LegacyTransaction  = LegacyTransaction(Nonce(0), 0, 0, None, 0, ByteString.empty)
  val stx1: SignedTransaction = SignedTransaction.sign(tx1, key1, None)
  val sender1: Address        = SignedTransaction.getSender(stx1)(TestCoreConfigs.chainId).get
  val response1: RpcLegacyTransactionResponse =
    RpcLegacyTransactionResponse.from(
      tx1,
      stx1.hash,
      sender1,
      stx1.signature,
      0,
      BlockHash(ByteString.empty),
      BlockNumber(0)
    )
  val tx2: LegacyTransaction  = LegacyTransaction(Nonce(2), 0, 0, None, 0, ByteString.empty)
  val stx2: SignedTransaction = SignedTransaction.sign(tx2, key2, None)
  val sender2: Address        = SignedTransaction.getSender(stx2)(TestCoreConfigs.chainId).get
  val response2: RpcLegacyTransactionResponse =
    RpcLegacyTransactionResponse.from(
      tx2,
      stx2.hash,
      sender2,
      stx2.signature,
      0,
      BlockHash(ByteString.empty),
      BlockNumber(0)
    )

  "TxPoolControllerSpec" when {
    "getContent() is called" should {
      "return 0 transaction when there is no transaction in the mempool" in { fixture =>
        val txPoolController = fixture.txPoolController

        txPoolController
          .getContent()
          .map(_.toOption.get)
          .map { response =>
            response.pending shouldBe Map()
            response.queued shouldBe Map()
          }
          .ioValue
      }

      "return only pending transactions if there is no queued transaction" in { fixture =>
        val txPoolController = fixture.txPoolController

        fixture.mempool.inner.addOne(stx1)

        txPoolController
          .getContent()
          .map(_.toOption.get)
          .map { response =>
            response.pending shouldBe Map(sender1 -> Map(Nonce(0) -> TxPoolController.toPooledTransaction(response1)))
            response.queued shouldBe Map()
          }
          .ioValue
      }

      "return only queued transactions if there is no pending transaction" in { fixture =>
        val txPoolController = fixture.txPoolController

        fixture.mempool.inner.addOne(stx2)

        txPoolController
          .getContent()
          .map(_.toOption.get)
          .map { response =>
            response.pending shouldBe Map()
            response.queued shouldBe Map(sender2 -> Map(Nonce(2) -> TxPoolController.toPooledTransaction(response2)))
          }
          .ioValue
      }

      "return pending and queued transaction from the mempool" in { fixture =>
        val txPoolController = fixture.txPoolController

        fixture.mempool.inner.addAll(Seq(stx1, stx2))

        txPoolController
          .getContent()
          .map(_.toOption.get)
          .map { response =>
            response.pending shouldBe Map(sender1 -> Map(Nonce(0) -> TxPoolController.toPooledTransaction(response1)))
            response.queued shouldBe Map(sender2 -> Map(Nonce(2) -> TxPoolController.toPooledTransaction(response2)))
          }
          .ioValue
      }
    }
  }

  case class FixtureParam(mempool: SlotBasedMempoolStub[IO, SignedTransaction], txPoolController: TxPoolController[IO])

  override def initFixture(dataSource: DataSource): FixtureParam = {
    val blockchainConfig = TestCoreConfigs.blockchainConfig
    val mempool          = SlotBasedMempoolStub[IO, SignedTransaction](ListBuffer.empty)
    val worldStateBuilder = {
      val mockedInMemoryWorldState = mock[InMemoryWorldState]
      (mockedInMemoryWorldState.getAccount _).expects(*).returning(None).anyNumberOfTimes()
      new WorldStateBuilder[IO] {
        override def getWorldStateForBlock(
            stateRoot: ByteString,
            blockContext: BlockContext
        ): Resource[IO, InMemoryWorldState] =
          Resource.pure(mockedInMemoryWorldState)
      }
    }
    val currentBranch = Signal.constant[IO, CurrentBranch](CurrentBranch(fixtures.GenesisBlock.header))
    val txPoolController: TxPoolController[IO] =
      new TxPoolControllerImpl[IO](mempool, worldStateBuilder, currentBranch, blockchainConfig)

    FixtureParam(mempool, txPoolController)
  }
}
