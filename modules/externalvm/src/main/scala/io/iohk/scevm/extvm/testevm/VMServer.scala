package io.iohk.scevm.extvm.testevm

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Framing, Keep, Sink, Source, Tcp}
import cats.effect.{IO, Sync}
import com.google.protobuf.{ByteString => GByteString}
import com.typesafe.config.ConfigFactory
import io.iohk.bytes.ByteString
import io.iohk.extvm.kevm.{kevm_msg => msg}
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.config.EthCompatibilityConfig
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.config.{BlockchainConfigForEvm, EvmConfig}
import io.iohk.scevm.exec.vm.{EVM, ProgramContext, ProgramResult, VM}
import io.iohk.scevm.extvm.Codecs.LengthPrefixSize
import io.iohk.scevm.extvm.Implicits._
import io.iohk.scevm.utils.Logger
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp

import java.nio.ByteOrder
import scala.concurrent.ExecutionContextExecutor

object VmServerApp extends Logger {

  implicit private val system: ActorSystem = ActorSystem("EVM_System")

  implicit private val vmServerExecutionContext: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  val QueueBufferSize: Int = 16 * 1024

  def main(args: Array[String]): Unit = {
    val config = ConfigFactory.load()

    val host = if (args.length > 0) args(0) else config.getString("scevm.vm.external-vm.host")
    val port = if (args.length > 1) args(1).toInt else config.getInt("scevm.vm.external-vm.port")

    Tcp().bind(host, port).runForeach(connection => handleConnection(connection.flow))
    log.info(s"VM server listening on $host:$port")
  }

  def handleConnection(connection: Flow[ByteString, ByteString, NotUsed]): Unit = {
    val (out, in) = Source
      .queue[ByteString](QueueBufferSize, OverflowStrategy.dropTail)
      .via(connection)
      .via(Framing.lengthField(LengthPrefixSize, 0, Int.MaxValue, ByteOrder.BIG_ENDIAN))
      .map(_.drop(LengthPrefixSize))
      .toMat(Sink.queue[ByteString]())(Keep.both)
      .run()

    new VMServer(new MessageHandler(in, out)).run()
  }
}

class VMServer(messageHandler: MessageHandler) extends Logger {

  private val vm: VM[IO, TransparentVMWorld, Storage] = EVM()

  private var defaultEvmExecutionRules: BlockchainConfigForEvm = _

  private[extvm] var processingThread: Thread = _

  private def processNextCall(): IO[Unit] =
    Sync[IO]
      .attempt(
        for {
          callContext <- IO.delay(messageHandler.awaitMessage[msg.CallContext])
          _            = log.debug("Server received msg: CallContext")

          context       = constructContextFromMsg(callContext)
          result       <- vm.run(context)
          callResultMsg = buildResultMsg(result)

          queryMsg = msg.VMQuery(query = msg.VMQuery.Query.CallResult(callResultMsg))
          _       <- IO.delay(messageHandler.sendMessage(queryMsg))
        } yield ()
      )
      .flatMap {
        case Right(_) => processNextCall()
        case Left(_)  => close()
      }

  private def awaitHello(): Unit = {
    val helloMsg = messageHandler.awaitMessage[msg.Hello]
    require(helloMsg.ethereumConfig.isDefined)
    defaultEvmExecutionRules = constructExecutionConfig(helloMsg.ethereumConfig.get)
  }

  def run(): Unit = {
    processingThread = new Thread(() => {
      import cats.effect.unsafe.implicits.global
      awaitHello()
      processNextCall().unsafeRunSync()
    })
    processingThread.start()
  }

  def close(): IO[Unit] = IO.delay {
    log.info("VM server connection closed")
    messageHandler.close()
  }

  // scalastyle:off method.length
  private def constructContextFromMsg(
      contextMsg: msg.CallContext
  ): ProgramContext[TransparentVMWorld, Storage] = {
    val blockContext: BlockContext =
      BlockContext(
        parentHash = BlockHash(ByteString.empty),
        number = contextMsg.blockHeader.get.number,
        slotNumber = Slot(0),
        gasLimit = contextMsg.blockHeader.get.gasLimit,
        coinbase = contextMsg.blockHeader.get.beneficiary,
        unixTimestamp = contextMsg.blockHeader.get.unixTimestamp.millisToTs
      )

    val executionConfig =
      contextMsg.ethereumConfig.map(constructExecutionConfig).getOrElse(defaultEvmExecutionRules)

    val vmConfig = EvmConfig.forBlock(blockContext.number, executionConfig)
    val world    = TransparentVMWorld(executionConfig.accountStartNonce, messageHandler)

    val recipientAddr: Option[Address] =
      Option(contextMsg.recipientAddr).filterNot(_.isEmpty).map(bytes => Address(bytes: ByteString))

    new ProgramContext(
      callerAddr = contextMsg.callerAddr,
      originAddr = contextMsg.callerAddr,
      recipientAddr = recipientAddr,
      maxPriorityFeePerGas = UInt256.Zero,
      startGas = contextMsg.gasProvided,
      intrinsicGas = BigInt(0),
      inputData = contextMsg.inputData,
      value = contextMsg.callValue,
      endowment = contextMsg.callValue,
      doTransfer = true,
      blockContext = blockContext,
      callDepth = 0,
      world = world,
      initialAddressesToDelete = Set(),
      evmConfig = vmConfig,
      originalWorld = world,
      warmAddresses = Set(),
      warmStorage = Set()
    )
  }

  private def buildResultMsg(result: ProgramResult[TransparentVMWorld, Storage]): msg.CallResult = {

    val logs = result.logs.map(l =>
      msg.LogEntry(address = l.loggerAddress, topics = l.logTopics.map(t => t: GByteString), data = l.data)
    )

    msg.CallResult(
      returnData = result.returnData,
      gasRemaining = result.gasRemaining,
      gasRefund = result.gasRefund,
      error = result.error.isDefined,
      modifiedAccounts = buildModifiedAccountsMsg(result.world),
      deletedAccounts = result.addressesToDelete.toList.map(a => a: GByteString),
      touchedAccounts = result.world.touchedAccounts.toList.map(a => a: GByteString),
      logs = logs,
      returnCode = ByteString.empty // TODO: result.status.statusCode.code
    )
  }

  private def buildModifiedAccountsMsg(world: TransparentVMWorld): Seq[msg.ModifiedAccount] = {
    val modifiedAddresses = world.accounts.keySet ++ world.codeRepo.keySet ++ world.storages.keySet
    modifiedAddresses.toList.map { address =>
      val acc            = world.getAccount(address)
      val storage        = world.getStorage(address)
      val storageUpdates = storage.storage.map { case (key, value) => msg.StorageUpdate(key, value) }.toList

      msg.ModifiedAccount(
        address = address,
        nonce = acc.map(_.nonce.value: GByteString).getOrElse(GByteString.EMPTY),
        balance = acc.map(_.balance: GByteString).getOrElse(GByteString.EMPTY),
        storageUpdates = storageUpdates,
        code = world.getCode(address)
      )
    }
  }

  private def constructExecutionConfig(conf: msg.EthereumConfig): BlockchainConfigForEvm =
    new BlockchainConfigForEvm(
      frontierBlockNumber = protoByteStringToBlockNumber(conf.frontierBlockNumber),
      homesteadBlockNumber = protoByteStringToBlockNumber(conf.homesteadBlockNumber),
      eip150BlockNumber = protoByteStringToBlockNumber(conf.eip150BlockNumber),
      spuriousDragonBlockNumber = BlockNumber(0),
      byzantiumBlockNumber = protoByteStringToBlockNumber(conf.byzantiumBlockNumber),
      constantinopleBlockNumber = protoByteStringToBlockNumber(conf.constantinopleBlockNumber),
      petersburgBlockNumber = protoByteStringToBlockNumber(conf.petersburgBlockNumber),
      istanbulBlockNumber = protoByteStringToBlockNumber(conf.istanbulBlockNumber),
      berlinBlockNumber = BlockNumber(0),
      londonBlockNumber = BlockNumber(0),
      maxCodeSize = Some(protoByteStringToBigInt(conf.maxCodeSize)),
      accountStartNonce = Nonce(conf.accountStartNonce),
      chainId = ChainId.unsafeFrom(protoByteStringToBigInt(conf.chainId)),
      ethCompatibility = EthCompatibilityConfig(UInt256.Zero, UInt256.Zero)
    )

}
