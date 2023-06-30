package io.iohk.scevm.extvm.testevm

import io.iohk.bytes.ByteString
import io.iohk.extvm.kevm.kevm_msg.{EthereumConfig, Hello, VMQuery}
import io.iohk.extvm.kevm.{kevm_msg => msg}
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.domain.{Account, Address, Nonce}
import io.iohk.scevm.exec.vm.Generators
import io.iohk.scevm.testing.TestCoreConfigs
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scalapb.GeneratedMessageCompanion

class VMServerSpec extends AnyFlatSpec with Matchers with MockFactory {

  import io.iohk.scevm.extvm.Implicits._

  "VMServer" should "start and await hello message" in new TestSetup {
    inSequence {
      (messageHandler.awaitMessage(_: GeneratedMessageCompanion[msg.Hello])).expects(*).returns(helloMsg)
      (messageHandler
        .awaitMessage(_: GeneratedMessageCompanion[msg.CallContext]))
        .expects(*)
        .throwing(new RuntimeException) // connection closed
      (messageHandler.close _).expects()
    }
    vmServer.run()
    vmServer.processingThread.join()
  }

  it should "handle incoming call context msg and respond with a call result" in new TestSetup {
    val blockContext = Generators.exampleBlockContext
    val blockHeaderMsg = msg.BlockHeader(
      beneficiary = blockContext.coinbase,
      difficulty = blockchainConfig.ethCompatibility.difficulty,
      number = blockContext.number,
      gasLimit = blockContext.gasLimit,
      unixTimestamp = blockContext.unixTimestamp.millis
    )

    val callContextMsg = msg.CallContext(
      callerAddr = Address("0x1001").bytes,
      recipientAddr = Address("0x1002").bytes,
      inputData = ByteString(),
      callValue = ByteString(BigInt(10).toByteArray),
      gasPrice = ByteString(BigInt(0).toByteArray),
      gasProvided = ByteString(BigInt(1000).toByteArray),
      blockHeader = Some(blockHeaderMsg)
    )

    val expectedModifiedAccount1 = msg.ModifiedAccount(
      address = Address("0x1001").bytes,
      nonce = ByteString(BigInt(0).toByteArray),
      balance = ByteString(BigInt(90).toByteArray),
      storageUpdates = Nil,
      code = ByteString()
    )

    val expectedModifiedAccount2 = msg.ModifiedAccount(
      address = Address("0x1002").bytes,
      nonce = ByteString(BigInt(0).toByteArray),
      balance = ByteString(BigInt(210).toByteArray),
      storageUpdates = Nil,
      code = ByteString()
    )

    val expectedCallResultMsg = msg.VMQuery(query =
      msg.VMQuery.Query.CallResult(
        msg.CallResult(
          returnData = ByteString(),
          returnCode = ByteString(),
          gasRemaining = ByteString(BigInt(1000).toByteArray),
          gasRefund = ByteString(BigInt(0).toByteArray),
          error = false,
          modifiedAccounts = Seq(expectedModifiedAccount1, expectedModifiedAccount2),
          deletedAccounts = Nil,
          touchedAccounts = Seq(Address("0x1001").bytes, Address("0x1002").bytes),
          logs = Nil
        )
      )
    )

    inSequence {
      (messageHandler.awaitMessage(_: GeneratedMessageCompanion[msg.Hello])).expects(*).returns(helloMsg)
      (messageHandler.awaitMessage(_: GeneratedMessageCompanion[msg.CallContext])).expects(*).returns(callContextMsg)
      expectAccountQuery(Address("0x1001"), response = Account(Nonce.Zero, 100))
      expectAccountQuery(Address("0x1002"), response = Account(Nonce.Zero, 200))
      expectCodeQuery(Address("0x1002"), response = ByteString())
      expectCodeQuery(Address("0x1001"), response = ByteString())
      (messageHandler.sendMessage _).expects(expectedCallResultMsg)
      (messageHandler
        .awaitMessage(_: GeneratedMessageCompanion[msg.CallContext]))
        .expects(*)
        .throwing(new RuntimeException) // connection closed
      (messageHandler.close _).expects()
    }

    vmServer.run()
    vmServer.processingThread.join()
  }

  trait TestSetup {
    val blockchainConfig: BlockchainConfig = TestCoreConfigs.blockchainConfig
    val forkBlockNumbers                   = blockchainConfig.forkBlockNumbers
    val ethereumConfig: EthereumConfig = msg.EthereumConfig(
      frontierBlockNumber = forkBlockNumbers.frontierBlockNumber,
      homesteadBlockNumber = forkBlockNumbers.homesteadBlockNumber,
      eip150BlockNumber = forkBlockNumbers.eip150BlockNumber,
      eip160BlockNumber = forkBlockNumbers.spuriousDragonBlockNumber,
      eip161BlockNumber = forkBlockNumbers.spuriousDragonBlockNumber,
      byzantiumBlockNumber = forkBlockNumbers.byzantiumBlockNumber,
      constantinopleBlockNumber = forkBlockNumbers.constantinopleBlockNumber,
      petersburgBlockNumber = forkBlockNumbers.petersburgBlockNumber,
      istanbulBlockNumber = forkBlockNumbers.istanbulBlockNumber,
      berlinBlockNumber = forkBlockNumbers.berlinBlockNumber,
      maxCodeSize = ByteString(100),
      chainId = com.google.protobuf.ByteString.copyFrom(ByteString(65.byteValue).toArray),
      accountStartNonce = blockchainConfig.genesisData.accountStartNonce.value
    )
    val helloMsg: Hello = msg.Hello(version = "2.0", ethereumConfig = Some(ethereumConfig))

    val messageHandler: MessageHandler = mock[MessageHandler]
    val vmServer                       = new VMServer(messageHandler)

    def expectAccountQuery(address: Address, response: Account): Unit = {
      val expectedQueryMsg = msg.VMQuery(VMQuery.Query.GetAccount(msg.GetAccount(address.bytes)))
      (messageHandler.sendMessage _).expects(expectedQueryMsg)
      val accountMsg =
        msg.Account(ByteString(response.nonce.value.toByteArray), ByteString(response.balance.toBigInt.toByteArray))
      (messageHandler.awaitMessage(_: GeneratedMessageCompanion[msg.Account])).expects(*).returns(accountMsg)
    }

    def expectCodeQuery(address: Address, response: ByteString): Unit = {
      val expectedQueryMsg = msg.VMQuery(VMQuery.Query.GetCode(msg.GetCode(address.bytes)))
      (messageHandler.sendMessage _).expects(expectedQueryMsg)
      val codeMsg = msg.Code(response)
      (messageHandler.awaitMessage(_: GeneratedMessageCompanion[msg.Code])).expects(*).returns(codeMsg)
    }
  }

}
