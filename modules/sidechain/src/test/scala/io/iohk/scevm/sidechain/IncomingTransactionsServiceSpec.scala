package io.iohk.scevm.sidechain

import cats.Applicative
import cats.data.StateT
import cats.effect.IO
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{Address, Nonce, ObftGenesisAccount, SignedTransaction, Slot, Token, UInt256}
import io.iohk.scevm.exec.vm.{IOEvmCall, ProgramResultError, TransactionSimulator, WorldType}
import io.iohk.scevm.sidechain.EVMTestUtils.EVMFixture
import io.iohk.scevm.sidechain.ValidIncomingCrossChainTransaction
import io.iohk.scevm.testing.{IOSupport, NormalPatience, TestCoreConfigs}
import io.iohk.scevm.trustlesssidechain.cardano.{MainchainBlockNumber, MainchainTxHash}
import io.janstenpickle.trace4cats.inject.Trace
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import java.security.SecureRandom

class IncomingTransactionsServiceSpec
    extends AsyncWordSpec
    with ScalaFutures
    with IOSupport
    with Matchers
    with EitherValues
    with NormalPatience {

  val bridgeAddress: Address  = Address("0x696f686b2e6d616d626100000000000000000000")
  val regularAccount: Address = Address("0x56a960bc95fc5b8153ebe6a76c049b555d674593")

  val regularAccountInitialBalance = 1000000L

  val ConversionRate: Long = math.pow(10, 9).toLong

  val fixture: EVMFixture = EVMFixture(
    bridgeAddress -> EVMTestUtils.loadContractCodeFromFile("Bridge.bin-runtime", 10000000L * ConversionRate),
    regularAccount -> ObftGenesisAccount(
      balance = regularAccountInitialBalance,
      code = None,
      nonce = None,
      storage = Map.empty
    )
  )
  val (prvKey, pubKey) = ECDSA.generateKeyPair(new SecureRandom())

  def dummyTxHash(index: Int): MainchainTxHash =
    MainchainTxHash(
      ByteString(Hex.decodeAsArrayUnsafe(f"4e3a6e7fdcb0d0efa17bf79c13aed2b4cb9baf37fb1aa2e39553d5$index%010d"))
    )

  "should return empty list if there are no incoming transactions at all" in {
    val incomingTransactionService = createService(List.empty)
    val bridgedTransactions =
      fixture.run(incomingTransactionService.getTransactions(Slot(1), prvKey)).ioValue
    bridgedTransactions shouldBe empty
  }

  "should convert a mainchain tx into an evm tx that when executed will increase balance of the recipient" in {
    val incomingTransaction =
      ValidIncomingCrossChainTransaction(regularAccount, Token(5), dummyTxHash(0), MainchainBlockNumber(38))
    val service = createService(mainchainTransactions = List(incomingTransaction))
    fixture
      .run(
        for {
          initialBalance      <- getAccountBalance(regularAccount)
          _                   <- StateT.liftF(IO.delay(initialBalance shouldBe Some(UInt256(regularAccountInitialBalance))))
          bridgedTransactions <- service.getTransactions(Slot(1), prvKey)
          balanceAfterExecution <-
            bridgedTransactions.traverse(
              executeTxOrFail(fixture.genesisBlock.header.beneficiary, _)
            ) >> getAccountBalance(regularAccount)
        } yield balanceAfterExecution shouldBe Some(
          UInt256(regularAccountInitialBalance).fillingAdd(UInt256(incomingTransaction.value.value * ConversionRate))
        )
      )
      .ioValue
  }

  private def getAccountBalance(address: Address) =
    StateT[IO, WorldType, Option[UInt256]](world => IO.delay(world -> world.getAccount(address).map(_.balance)))

  private def createService(mainchainTransactions: List[ValidIncomingCrossChainTransaction]) = {
    val bridge =
      new BridgeContract[IOEvmCall, ECDSA](
        TestCoreConfigs.blockchainConfig.chainId,
        bridgeAddress,
        TransactionSimulator[IO](
          TestCoreConfigs.blockchainConfig,
          EVMTestUtils.vm,
          IO.pure(_)
        )
      )
    implicit val trace: Trace[IO]   = Trace.Implicits.noop[IO]
    val incomingTransactionsService = new IncomingCrossChainTransactionsProviderStub[IO](mainchainTransactions)
    new IncomingTransactionsServiceImpl[IO](
      incomingTransactionsService,
      bridge
    )
  }

  private def executeTxOrFail(
      address: Address,
      transaction: SignedTransaction
  ): StateT[IO, WorldType, Either[ProgramResultError, ByteString]] =
    StateT(world =>
      fixture
        .executeTransactionWithWorld(world)(transaction.transaction, address)
        .flatMap(pr =>
          pr.toEither match {
            case Left(value) => IO.raiseError(new RuntimeException(new String(value.data.toArray)))
            case Right(_)    => IO.pure((pr.world, pr.toEither))
          }
        )
    )

  implicit class RichStatefulService(service: IncomingTransactionsService[IO]) {
    def getTransactions(slot: Slot, privateKey: ECDSA.PrivateKey): StateT[IO, WorldType, List[SignedTransaction]] =
      StateT { world =>
        val startNonce = world.getAccount(Address.fromPrivateKey(privateKey)).map(_.nonce).getOrElse(Nonce.Zero)
        service
          .getTransactions(world.modifyBlockContext(_.copy(slotNumber = slot)))
          .map(transactions =>
            transactions
              .zip(Iterator.iterate(startNonce)(_.increaseOne))
              .map { case (tx, nonce) =>
                SignedTransaction.sign(tx.toTransaction(nonce), privateKey, Some(tx.chainId))
              }
          )
          .map(world -> _)
      }
  }

}

class IncomingCrossChainTransactionsProviderStub[F[_]: Applicative](
    transactions: List[ValidIncomingCrossChainTransaction]
) extends IncomingCrossChainTransactionsProvider[F] {
  override def getTransactions(
      word: WorldType
  ): F[List[ValidIncomingCrossChainTransaction]] = transactions.pure
}
