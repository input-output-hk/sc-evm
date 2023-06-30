package io.iohk.scevm.extvm.iele

import com.google.protobuf.{ByteString => ProtoByteString}
import io.iohk.bytes.ByteString
import io.iohk.extvm.iele.{iele_msg => msg}
import io.iohk.scevm.domain.{Address, BlockContext, Nonce, TransactionLogEntry}
import io.iohk.scevm.exec.vm._
import io.iohk.scevm.extvm.Implicits._
import io.iohk.scevm.extvm.statuses.VMStatus

import scala.concurrent.duration.Duration

object IELEMessageConstructors {

  def constructHello(protoApiVersion: String): msg.Hello =
    msg.Hello(
      protoApiVersion,
      Some(msg.IeleConfig())
    )

  def constructCallContext[W <: WorldState[W, S], S <: Storage[S]](
      programContext: ProgramContext[W, S]
  ): msg.CallContext =
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
      ieleConfig = Some(msg.IeleConfig())
    )

  def constructStatus(statusCode: ByteString): VMStatus =
    IELEStatus(statusCode)

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
  ): ProgramResult[W, S] =
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
      Set.empty,
      Set.empty
    )

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
