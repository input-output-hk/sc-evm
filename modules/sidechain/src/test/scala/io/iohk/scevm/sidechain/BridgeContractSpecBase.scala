package io.iohk.scevm.sidechain

import cats.data.{IndexedStateT, StateT}
import cats.effect.IO
import com.softwaremill.diffx.scalatest.DiffShouldMatcher
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{Address, EpochPhase, ObftGenesisAccount, SignedTransaction, Slot, UInt256}
import io.iohk.scevm.exec.vm.{IOEvmCall, ProgramResultError, TransactionSimulator, WorldType}
import io.iohk.scevm.sidechain.BridgeContract.OutgoingTransactionIdDecoder
import io.iohk.scevm.sidechain.EVMTestUtils.EVMFixture
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sidechain.transactions.{OutgoingTxId, OutgoingTxRecipient}
import io.iohk.scevm.solidity.{SolidityAbi, SolidityAbiDecoder, SolidityAbiEncoder}
import io.iohk.scevm.testing.{IOSupport, NormalPatience, TestCoreConfigs}
import io.iohk.scevm.trustlesssidechain.cardano.MainchainTxHash
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

trait BridgeContractSpecBase
    extends AnyWordSpec
    with Matchers
    with DiffShouldMatcher
    with IOSupport
    with ScalaFutures
    with NormalPatience {

  val ConversionRate: Long = math.pow(10, 9).toLong

  val bridgeAddress: Address       = Address("0x696f686b2e6d616d626100000000000000000000")
  val indirectCallAddress: Address = Address("0x696f686b2e6d616d626100000000000000000001")
  val regularAccount: Address      = Address("0x0000000000000000000000000000000000001234")

  val mainchainTxHash: MainchainTxHash =
    MainchainTxHash(Hex.decodeUnsafe("0011223344556677889900112233445566778899001122334455667788990011"))

  /* Setting the initial value of Bridge.sol variable 'currentTxsBatchMRHEpoch' to max uint256.
   * Because we load code directly to the bridge address, it is not begin initialized by the EVM,
   * so it's important to have it set, otherwise the code doesn't work for epoch 0.
   * Production code deals with it by setting the value in the genesis.json file.
   *
   * To obtain the key for mapping read 'slot' of variable to be set in the output of
   * `solc --storage-layout ./src/solidity-bridge/contracts/Bridge.sol` command.
   */
  val bridgeAccountStorage: Map[UInt256, UInt256] = {
    val currentTxsBatchMRHEpochMemorySlot = UInt256(3)
    Map(currentTxsBatchMRHEpochMemorySlot -> UInt256.MaxValue)
  }
  val fixture: EVMFixture = EVMFixture(
    bridgeAddress -> EVMTestUtils
      .loadContractCodeFromFile("Bridge.bin-runtime", 10000000L * ConversionRate, bridgeAccountStorage),
    indirectCallAddress -> EVMTestUtils.loadContractCodeFromFile("IndirectCall.bin-runtime"),
    regularAccount      -> ObftGenesisAccount(1000000L * ConversionRate, None, None, Map.empty)
  )

  val bridgeContract: BridgeContract[IOEvmCall, ECDSA] = new BridgeContract(
    TestCoreConfigs.chainId,
    bridgeAddress,
    TransactionSimulator[IO](TestCoreConfigs.blockchainConfig, EVMTestUtils.vm, IO.pure)
  )

  implicit class BridgeContractOps(bridgeContract: BridgeContract[IOEvmCall, ECDSA]) {
    def lock(value: Long, recipient: OutgoingTxRecipient): IndexedStateT[IO, WorldType, WorldType, OutgoingTxId] = {
      implicit val recipientSolidityEncoder: SolidityAbiEncoder[OutgoingTxRecipient] = OutgoingTxRecipient.deriving
      val bridgePayload                                                              = SolidityAbi.solidityCall(BridgeContract.Methods.Lock, recipient)
      val result                                                                     = fixture.callContract(bridgeAddress, bridgePayload, regularAccount, value)
      result.map(either => either.map(bytes => SolidityAbiDecoder[Tuple1[OutgoingTxId]].decode(bytes)._1)).flatMapF {
        case Left(value)  => IO.raiseError(new RuntimeException(value.error.toString))
        case Right(value) => IO.pure(value)
      }
    }

    def setContractState(
        epoch: SidechainEpoch,
        epochPhase: EpochPhase,
        slot: Slot = Slot(100)
    ): StateT[IO, WorldType, Unit] =
      fixture.updateWorld(state =>
        IO(
          BridgeContract
            .prepareState(bridgeAddress)(state.modifyBlockContext(_.copy(slotNumber = slot)), epoch, epochPhase)
        )
      )

    def getMerkleRootForEpoch(epoch: SidechainEpoch): StateT[IO, WorldType, Option[RootHash]] = fixture.withWorld(
      bridgeContract.getTransactionsMerkleRootHash(epoch).run
    )
  }

  def executeTransaction(
      transaction: SignedTransaction,
      chainId: ChainId
  ): StateT[IO, WorldType, Either[ProgramResultError, ByteString]] = fixture.callContract(
    transaction.transaction.receivingAddress.get,
    transaction.transaction.payload,
    SignedTransaction.getSender(transaction)(chainId).get
  )

}
