package io.iohk.scevm.exec.vm

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.config.EvmConfig

object ProgramContext {
  def apply[W <: WorldState[W, S], S <: Storage[S]](
      transaction: Transaction,
      blockContext: BlockContext,
      senderAddress: Address,
      world: W,
      evmConfig: EvmConfig
  ): ProgramContext[W, S] = {
    val accessList = Transaction.accessList(transaction)
    val intrinsicGas = evmConfig.calcTransactionIntrinsicGas(
      transaction.payload,
      transaction.isContractInit,
      accessList
    )
    val gasLimit = transaction.gasLimit - intrinsicGas

    val maxPriorityFee = transaction match {
      case transaction: LegacyTransaction => transaction.gasPrice
      case transaction: TransactionType01 => transaction.gasPrice
      case transaction: TransactionType02 => transaction.maxPriorityFeePerGas
    }

    ProgramContext(
      callerAddr = senderAddress,
      originAddr = senderAddress,
      recipientAddr = transaction.receivingAddress,
      maxPriorityFeePerGas = UInt256(maxPriorityFee),
      startGas = gasLimit,
      intrinsicGas = intrinsicGas,
      inputData = transaction.payload,
      value = UInt256(transaction.value),
      endowment = UInt256(transaction.value),
      doTransfer = true,
      blockContext = blockContext,
      callDepth = 0,
      world = world,
      initialAddressesToDelete = Set(),
      evmConfig = evmConfig,
      originalWorld = world,
      warmAddresses = accessList.map(_.address).toSet,
      warmStorage = accessList.flatMap(i => i.storageKeys.map((i.address, _))).toSet
    )
  }
}

/** Input parameters to a program executed on the EVM. Apart from the code itself
  * it should have all (interfaces to) the data accessible from the EVM.
  *
  * Execution constants, see section 9.3 in Yellow Paper for more detail.
  *
  * @param callerAddr               I_s: address of the account which caused the code to be executing
  * @param originAddr               I_o: sender address of the transaction that originated this execution
  * @param inputData                I_d
  * @param value                    I_v
  * @param blockContext             I_H
  * @param callDepth                I_e
  *
  *                                 Additional parameters:
  * @param maxPriorityFeePerGas     maximum fee per gas to give to miners to incentivize them to include transactions
  * @param recipientAddr            recipient of the call, empty if contract creation
  * @param endowment                value that appears to be transferred between accounts,
  *                                 if CALLCODE - equal to callValue (but is not really transferred)
  *                                 if DELEGATECALL - always zero
  *                                 if STATICCALL - always zero
  *                                 otherwise - equal to value
  * @param doTransfer               false for CALLCODE/DELEGATECALL/STATICCALL, true otherwise
  * @param startGas                 initial gas for the execution
  * @param intrinsicGas             transaction intrinsic gas. See YP section 6.2
  * @param world                    provides interactions with world state
  * @param initialAddressesToDelete contains initial set of addresses to delete (from lower depth calls)
  * @param evmConfig                evm config
  * @param staticCtx                a flag to indicate static context (EIP-214)
  * @param originalWorld            state of the world at the beginning of the current transaction, read-only,
  *                                 needed for https://eips.ethereum.org/EIPS/eip-1283
  */
final case class ProgramContext[W <: WorldState[W, S], S <: Storage[S]](
    callerAddr: Address,
    originAddr: Address,
    recipientAddr: Option[Address],
    maxPriorityFeePerGas: UInt256,
    startGas: BigInt,
    intrinsicGas: BigInt,
    inputData: ByteString,
    value: UInt256,
    endowment: UInt256,
    doTransfer: Boolean,
    blockContext: BlockContext,
    callDepth: Int,
    world: W,
    initialAddressesToDelete: Set[Address],
    evmConfig: EvmConfig,
    staticCtx: Boolean = false,
    originalWorld: W,
    warmAddresses: Set[Address],
    warmStorage: Set[(Address, BigInt)]
)
