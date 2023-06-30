package io.iohk.scevm.extvm.kevm

import com.google.protobuf.{ByteString => ProtoByteString}
import io.iohk.bytes.ByteString
import io.iohk.extvm.kevm.{kevm_msg => msg}
import io.iohk.scevm.config._
import io.iohk.scevm.domain.{Address, BlockContext, Nonce, TransactionLogEntry}
import io.iohk.scevm.exec.config._
import io.iohk.scevm.exec.vm._
import io.iohk.scevm.extvm.Implicits._

import scala.concurrent.duration.Duration

object KEVMMessageConstructors {

  def constructHello(blockchainConfig: BlockchainConfig, protoApiVersion: String): msg.Hello =
    msg.Hello(
      protoApiVersion,
      Some(constructEthereumConfigMsg(BlockchainConfigForEvm(blockchainConfig)))
    )

  def constructCallContext[W <: WorldState[W, S], S <: Storage[S]](
      programContext: ProgramContext[W, S]
  ): msg.CallContext = {
    val txType =
      if (programContext.warmAddresses.isEmpty && programContext.warmStorage.isEmpty) msg.CallContext.TxType.LEGACY
      else msg.CallContext.TxType.ACCESSLIST

    val config = constructEthereumConfigMsg(programContext.evmConfig.blockchainConfig)

    msg.CallContext(
      callerAddr = programContext.callerAddr,
      recipientAddr = programContext.recipientAddr
        .map(addr => ProtoByteString.copyFrom(addr.toArray))
        .getOrElse(com.google.protobuf.ByteString.EMPTY),
      inputData = ProtoByteString.copyFrom(programContext.inputData.toArray),
      callValue = ProtoByteString.copyFrom(programContext.value.toByteArray),
      gasPrice = BigInt(0),
      gasProvided = programContext.startGas,
      blockHeader = Some(constructBlockHeaderMsg(programContext.blockContext)),
      ethereumConfig = Some(config),
      txType = txType
    )
  }

  private def constructEthereumConfigMsg(evmBlockchainConfig: BlockchainConfigForEvm): msg.EthereumConfig =
    msg.EthereumConfig(
      frontierBlockNumber = evmBlockchainConfig.frontierBlockNumber,
      homesteadBlockNumber = evmBlockchainConfig.homesteadBlockNumber,
      eip150BlockNumber = evmBlockchainConfig.eip150BlockNumber,
      eip160BlockNumber = BigInt(0),
      eip161BlockNumber = BigInt(0),
      byzantiumBlockNumber = evmBlockchainConfig.byzantiumBlockNumber,
      constantinopleBlockNumber = evmBlockchainConfig.constantinopleBlockNumber,
      petersburgBlockNumber = evmBlockchainConfig.petersburgBlockNumber,
      istanbulBlockNumber = evmBlockchainConfig.istanbulBlockNumber,
      berlinBlockNumber = evmBlockchainConfig.berlinBlockNumber,
      maxCodeSize = BigInt(0),
      accountStartNonce = evmBlockchainConfig.accountStartNonce.value,
      chainId = ProtoByteString.copyFrom(Array(evmBlockchainConfig.chainId.value))
    )

  def constructBlockHeaderMsg(blockContext: BlockContext): msg.BlockHeader =
    msg.BlockHeader(
      beneficiary = blockContext.coinbase,
      difficulty = BigInt(0),
      number = blockContext.number,
      gasLimit = blockContext.gasLimit,
      unixTimestamp = blockContext.unixTimestamp.toSeconds
    )

  def constructProgramResult[W <: WorldState[W, S], S <: Storage[S]](
      world: W,
      callResult: msg.CallResult
  ): ProgramResult[W, S] = {
    val accessedResultTuple = callResult.accessList
      .map { accessList =>
        val addresses: Set[Address] = accessList.addresses.map(a => a: Address).toSet
        val locations: Set[(Address, BigInt)] = accessList.storageLocations.map { loc =>
          (loc.address: Address, loc.storageLocation: BigInt)
        }.toSet
        (addresses, locations)
      }
      .getOrElse((Set.empty[Address], Set.empty[(Address, BigInt)]))

    ProgramResult(
      callResult.returnData,
      callResult.gasRemaining,
      applyAccountChanges[W, S](world, callResult),
      callResult.deletedAccounts.map(a => a: Address).toSet,
      callResult.logs.map(l => TransactionLogEntry(l.address, l.topics.map(t => t: ByteString), l.data)),
      Nil,
      callResult.gasRefund,
      Duration.Zero,
      if (callResult.error) Some(RevertOccurs) else None,
      None,
      accessedResultTuple._1,
      accessedResultTuple._2
    )
  }

  private def applyAccountChanges[W <: WorldState[W, S], S <: Storage[S]](
      world: W,
      callResult: msg.CallResult
  ): W =
    callResult.modifiedAccounts
      .foldLeft(world) { (w, change) =>
        val address        = change.address
        val initialAccount = w.getAccount(address).getOrElse(w.getEmptyAccount)
        val updatedWorld = w
          .saveAccount(
            address,
            if (change.nonce.isEmpty) initialAccount
            else initialAccount.copy(nonce = Nonce(change.nonce), balance = change.balance)
          )
          .saveStorage(
            address,
            change.storageUpdates.foldLeft(w.getStorage(address)) { (s, update) =>
              s.store(update.offset, update.data)
            }
          )
        if (change.code.isEmpty) updatedWorld else updatedWorld.saveCode(address, change.code)
      }
      .touchAccounts(callResult.touchedAccounts.map(a => a: Address): _*)

}
