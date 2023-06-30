package io.iohk.scevm.extvm.kevm

import cats.effect.std.Semaphore
import cats.effect.{Concurrent, IO, Ref, Resource}
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.extvm.kevm.kevm_msg.{CallResult, VMQuery}
import io.iohk.extvm.kevm.{kevm_msg => msg}
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.config.{BlockchainConfigForEvm, EvmConfig}
import io.iohk.scevm.exec.utils.MockVmInput
import io.iohk.scevm.exec.vm.{Generators, MockStorage, MockWorldState, ProgramContext}
import io.iohk.scevm.extvm.Implicits._
import io.iohk.scevm.extvm.MessageSocket
import io.iohk.scevm.extvm.VmConfig.KEVMConfig
import io.iohk.scevm.extvm.kevm.KEVMClient.VMConnection
import io.iohk.scevm.testing.{IOSupport, NormalPatience, TestCoreConfigs}
import io.iohk.scevm.utils.Logger
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import scalapb.GeneratedMessage

class KEVMClientSpec extends AnyFlatSpecLike with Logger with IOSupport with ScalaFutures with NormalPatience {

  import Constants._

  "KEVMClient" should "run programs" in {
    assertIOSync {
      fixtureResource.use { case Fixture(externalVM, vmSocket) =>
        val programContext =
          ProgramContext[MockWorldState, MockStorage](tx, blockHeader, senderAddress, emptyWorld, evmConfig)

        val expectedReceives = Seq(resultQueryMsg)
        val expectedSends    = Seq(KEVMMessageConstructors.constructCallContext(programContext))

        vmSocket.load(expectedReceives, expectedSends) >>
          externalVM.run(programContext).map { programResult =>
            assert(programResult.error.isEmpty)
            assert(programResult.returnData == protoByteStringToByteString(callResultMsg.returnData))
            assert(programResult.gasRemaining == protoByteStringToBigInt(callResultMsg.gasRemaining))
            assert(programResult.gasRefund == protoByteStringToBigInt(callResultMsg.gasRefund))
          }
      }
    }
  }

  it should "handle account queries" in {
    assertIOSync {
      fixtureResource.use { case Fixture(externalVM, vmSocket) =>
        val testQueryAccountAddress = io.iohk.scevm.domain.Address("0x129982FF")
        val testQueryAccount        = io.iohk.scevm.domain.Account(nonce = Nonce(11), balance = 99999999)
        val programContext = ProgramContext[MockWorldState, MockStorage](
          tx,
          blockHeader,
          senderAddress,
          emptyWorld.saveAccount(testQueryAccountAddress, testQueryAccount),
          evmConfig
        )

        val accountQueryMsg =
          msg.VMQuery(query = msg.VMQuery.Query.GetAccount(msg.GetAccount(testQueryAccountAddress.bytes)))
        val expectedAccountResponseMsg = msg.Account(
          nonce = ByteString(testQueryAccount.nonce.value.toByteArray),
          balance = ByteString(testQueryAccount.balance.toBigInt.toByteArray),
          codeEmpty = true
        )

        val expectedReceives = Seq(accountQueryMsg, resultQueryMsg)
        val expectedSends =
          Seq(KEVMMessageConstructors.constructCallContext(programContext), expectedAccountResponseMsg)

        vmSocket.load(expectedReceives, expectedSends) >>
          externalVM.run(programContext).map { result =>
            assert(result.error.isEmpty)
          }
      }
    }
  }

  it should "handle storage queries" in {
    assertIOSync {
      fixtureResource.use { case Fixture(externalVM, vmSocket) =>
        val testStorageAddress = Address("0x99999999444444ffcc")
        val testStorageOffset  = BigInt(123)
        val testStorageValue   = BigInt(5918918239L)
        val programContext = ProgramContext[MockWorldState, MockStorage](
          tx,
          blockHeader,
          senderAddress,
          emptyWorld.saveStorage(testStorageAddress, MockStorage().store(testStorageOffset, testStorageValue)),
          evmConfig
        )

        val storageQueryMsg =
          msg.VMQuery(query =
            msg.VMQuery.Query.GetStorageData(msg.GetStorageData(testStorageAddress, testStorageOffset))
          )
        val expectedStorageDataResponseMsg = msg.StorageData(ByteString(testStorageValue.toByteArray))

        val expectedReceives = Seq(storageQueryMsg, resultQueryMsg)
        val expectedSends =
          Seq(
            KEVMMessageConstructors.constructCallContext(programContext),
            expectedStorageDataResponseMsg
          )

        vmSocket.load(expectedReceives, expectedSends) >>
          externalVM.run(programContext).map { result =>
            assert(result.error.isEmpty)
          }
      }
    }
  }

  it should "handle code queries" in {
    assertIOSync {
      fixtureResource.use { case Fixture(externalVM, vmSocket) =>
        val testCodeAddress = Address("0x1234")
        val testCodeValue   = ByteString(Hex.decodeAsArrayUnsafe("11223344991191919191919129129facefc122"))
        val programContext = ProgramContext[MockWorldState, MockStorage](
          tx,
          blockHeader,
          senderAddress,
          emptyWorld.saveCode(testCodeAddress, testCodeValue),
          evmConfig
        )

        val getCodeQueryMsg         = msg.VMQuery(query = msg.VMQuery.Query.GetCode(msg.GetCode(testCodeAddress)))
        val expectedCodeResponseMsg = msg.Code(testCodeValue)

        val expectedReceives = Seq(getCodeQueryMsg, resultQueryMsg)
        val expectedSends    = Seq(KEVMMessageConstructors.constructCallContext(programContext), expectedCodeResponseMsg)

        vmSocket.load(expectedReceives, expectedSends) >>
          externalVM.run(programContext).map { result =>
            assert(result.error.isEmpty)
          }
      }
    }
  }

  it should "handle blockhash queries" in {
    assertIOSync {
      fixtureResource.use { case Fixture(externalVM, vmSocket) =>
        val testNumber = 87
        val world      = emptyWorld.copy(numberOfHashes = 100)
        val programContext =
          ProgramContext[MockWorldState, MockStorage](tx, blockHeader, senderAddress, world, evmConfig)

        val getBlockhashQueryMsg         = msg.VMQuery(query = msg.VMQuery.Query.GetBlockhash(msg.GetBlockhash(testNumber)))
        val expectedBlockhashResponseMsg = msg.Blockhash(world.getBlockHash(UInt256(testNumber)).get.bytes)

        val expectedReceives = Seq(getBlockhashQueryMsg, resultQueryMsg)
        val expectedSends =
          Seq(KEVMMessageConstructors.constructCallContext(programContext), expectedBlockhashResponseMsg)

        vmSocket.load(expectedReceives, expectedSends) >>
          externalVM.run(programContext).map { result =>
            assert(result.error.isEmpty)
          }
      }
    }
  }

  case class Fixture(
      externalVM: KEVMClient[MockWorldState, MockStorage],
      vmSocket: MockMessageSocket[IO, VMQuery, GeneratedMessage]
  )

  def fixtureResource: Resource[IO, Fixture] =
    Resource.eval {
      for {
        mockMessageSocket <- MockMessageSocket[IO, VMQuery, GeneratedMessage]
        semaphore         <- Semaphore[IO](1)
        vmConnectionRef   <- Ref.of[IO, Option[VMConnection]]((mockMessageSocket, IO.unit).some)
      } yield Fixture(
        new KEVMClient[MockWorldState, MockStorage](
          kevmConfig,
          blockchainConfig,
          semaphore,
          vmConnectionRef
        ),
        mockMessageSocket
      )
    }

  class MockMessageSocket[F[_]: Concurrent, In, Out](
      insRef: Ref[F, Option[Seq[In]]],
      outsRef: Ref[F, Option[Seq[Out]]]
  ) extends MessageSocket[F, In, Out] {

    def load(ins: Seq[In], outs: Seq[Out]): F[Unit] = insRef.set(ins.some) >> outsRef.set(outs.some)

    def send(out: Out): F[Unit] = outsRef.update {
      case Some(outs) =>
        outs match {
          case head :: tail =>
            assert(head === out)
            tail.some
          case _ => fail(s"Unexpected message $out sent")
        }
      case None => throw new IllegalStateException("Expected messages have not been loaded into the mock socket.")
    }

    def receive: F[In] =
      insRef.get >>= {
        case Some(ins) =>
          ins match {
            case head :: tail => insRef.set(Some(tail)).as(head)
            case _            => fail(s"Unexpected receive")
          }
        case _ =>
          new IllegalStateException("Expected messages have not been loaded into the mock socket.").raiseError[F, In]
      }
  }

  object MockMessageSocket {
    def apply[F[_]: Concurrent, In, Out]: F[MockMessageSocket[F, In, Out]] =
      for {
        insRef  <- Ref.of(none[Seq[In]])
        outsRef <- Ref.of(none[Seq[Out]])
      } yield new MockMessageSocket[F, In, Out](insRef, outsRef)
  }

  private object Constants {

    val kevmConfig: KEVMConfig = KEVMConfig("127.0.0.1", 0, "")

    val blockHeader = Generators.exampleBlockContext

    val emptyWorld: MockWorldState = MockWorldState()

    val blockchainConfig: BlockchainConfig             = TestCoreConfigs.blockchainConfig
    val blockchainConfigForEvm: BlockchainConfigForEvm = BlockchainConfigForEvm(blockchainConfig)
    val evmConfig: EvmConfig                           = EvmConfig.FrontierConfigBuilder(blockchainConfigForEvm)

    val senderAddress: Address = Address("0x01")
    val tx: Transaction        = MockVmInput.mockTransaction(ByteString(""), 10, 123)

    val callResultMsg: CallResult = msg.CallResult(
      returnData = ByteString("0011"),
      returnCode = ByteString(0),
      gasRemaining = ByteString(BigInt(99).toByteArray),
      gasRefund = ByteString(BigInt(120).toByteArray),
      modifiedAccounts = Nil
    )

    val resultQueryMsg: VMQuery = msg.VMQuery(query = msg.VMQuery.Query.CallResult(callResultMsg))

  }
}
