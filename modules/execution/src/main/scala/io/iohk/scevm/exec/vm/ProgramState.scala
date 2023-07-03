package io.iohk.scevm.exec.vm

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Address, TransactionLogEntry, UInt256}
import io.iohk.scevm.exec.config.EvmConfig
import io.iohk.scevm.exec.vm.tracing.Tracer.TracingError

import scala.concurrent.duration.Duration

object ProgramState {
  def apply[W <: WorldState[W, S], S <: Storage[S]](
      call: VM.Call[W, S],
      create: VM.Create[W, S],
      selfDestruct: VM.SelfDestruct[W, S],
      context: ProgramContext[W, S],
      env: ExecEnv
  ): ProgramState[W, S] =
    ProgramState(
      env = env,
      gas = env.startGas,
      world = context.world,
      staticCtx = context.staticCtx,
      addressesToDelete = context.initialAddressesToDelete,
      originalWorld = context.originalWorld,
      accessedAddresses = PrecompiledContracts.getContracts(context).keySet ++ Set(
        context.originAddr,
        context.recipientAddr.getOrElse(context.callerAddr)
      ) ++ context.warmAddresses,
      accessedStorageKeys = context.warmStorage,
      call = call,
      create = create,
      selfDestruct = selfDestruct
    )
}

/** Intermediate state updated with execution of each opcode in the program
  *
  * @param vm                         the VM
  * @param env                        program constants
  * @param gas                        current gas for the execution
  * @param world                      world state
  * @param addressesToDelete          list of addresses of accounts scheduled to be deleted
  * @param stack                      current stack
  * @param memory                     current memory
  * @param pc                         program counter - an index of the opcode in the program to be executed
  * @param returnData                 data to be returned from the program execution
  * @param gasRefund                  the amount of gas to be refunded after execution (not sure if a separate field is required)
  * @param internalTxs                list of internal transactions (for debugging/tracing)
  * @param halted                     a flag to indicate program termination
  * @param staticCtx                  a flag to indicate static context (EIP-214)
  * @param error                      indicates whether the program terminated abnormally
  * @param originalWorld              state of the world at the beginning og the current transaction, read-only,
  * @param accessedAddresses          set of addresses which have already been accessed in this transaction (EIP-2929)
  * @param accessedStorageKeys        set of storage slots which have already been accessed in this transaction (EIP-2929)
  *                                   needed for https://eips.ethereum.org/EIPS/eip-1283
  */
final case class ProgramState[W <: WorldState[W, S], S <: Storage[S]](
    env: ExecEnv,
    gas: BigInt,
    world: W,
    addressesToDelete: Set[Address],
    stack: Stack = Stack.empty(),
    memory: Memory = Memory.empty,
    pc: Int = 0,
    returnData: ByteString = ByteString.empty,
    gasRefund: BigInt = 0,
    internalTxs: Vector[InternalTransaction] = Vector.empty,
    logs: Vector[TransactionLogEntry] = Vector.empty,
    halted: Boolean = false,
    staticCtx: Boolean = false,
    error: Option[ProgramError] = None,
    tracingError: Option[TracingError] = None,
    originalWorld: W,
    accessedAddresses: Set[Address],
    accessedStorageKeys: Set[(Address, BigInt)],
    call: VM.Call[W, S],
    create: VM.Create[W, S],
    selfDestruct: VM.SelfDestruct[W, S]
) {

  def config: EvmConfig = env.evmConfig

  def ownAddress: Address = env.ownerAddr

  def ownBalance: UInt256 = world.getBalance(ownAddress)

  def storage: S = world.getStorage(ownAddress)

  def gasUsed: BigInt = env.startGas - gas

  def withWorld(updated: W): ProgramState[W, S] =
    copy(world = updated)

  def withStorage(updated: S): ProgramState[W, S] =
    withWorld(world.saveStorage(ownAddress, updated))

  def program: Program = env.program

  def inputData: ByteString = env.inputData

  def spendGas(amount: BigInt): ProgramState[W, S] =
    copy(gas = gas - amount)

  def refundGas(amount: BigInt): ProgramState[W, S] =
    copy(gasRefund = gasRefund + amount)

  def step(i: Int = 1): ProgramState[W, S] =
    copy(pc = pc + i)

  def goto(i: Int): ProgramState[W, S] =
    copy(pc = i)

  def withStack(stack: Stack): ProgramState[W, S] =
    copy(stack = stack)

  def withMemory(memory: Memory): ProgramState[W, S] =
    copy(memory = memory)

  def withError(error: ProgramError): ProgramState[W, S] =
    copy(error = Some(error), returnData = ByteString.empty, halted = true)

  def withTracingError(error: TracingError): ProgramState[W, S] =
    copy(tracingError = Some(error), returnData = ByteString.empty, halted = true)

  def withReturnData(data: ByteString): ProgramState[W, S] =
    copy(returnData = data)

  def withAddressToDelete(addr: Address): ProgramState[W, S] =
    copy(addressesToDelete = addressesToDelete + addr)

  def withAddressesToDelete(addresses: Set[Address]): ProgramState[W, S] =
    copy(addressesToDelete = addressesToDelete ++ addresses)

  def withLog(log: TransactionLogEntry): ProgramState[W, S] =
    copy(logs = logs :+ log)

  def withLogs(log: Seq[TransactionLogEntry]): ProgramState[W, S] =
    copy(logs = logs ++ log)

  def withInternalTxs(txs: Seq[InternalTransaction]): ProgramState[W, S] =
    if (config.traceInternalTransactions) copy(internalTxs = internalTxs ++ txs) else this

  def halt: ProgramState[W, S] =
    copy(halted = true)

  def revert(data: ByteString): ProgramState[W, S] =
    copy(error = Some(RevertOccurs), returnData = data, halted = true)

  def addAccessedAddress(addr: Address): ProgramState[W, S] =
    copy(accessedAddresses = accessedAddresses + addr)

  def addAccessedStorageKey(addr: Address, storageKey: BigInt): ProgramState[W, S] =
    copy(accessedStorageKeys = accessedStorageKeys + ((addr, storageKey)))

  def addAccessedAddresses(addresses: Set[Address]): ProgramState[W, S] =
    copy(accessedAddresses = accessedAddresses ++ addresses)

  def addAccessedStorageKeys(storageKeys: Set[(Address, BigInt)]): ProgramState[W, S] =
    copy(accessedStorageKeys = accessedStorageKeys ++ storageKeys)

  def toResult: ProgramResult[W, S] =
    ProgramResult[W, S](
      returnData,
      if (error.exists(_.useWholeGas)) 0 else gas,
      world,
      addressesToDelete,
      logs,
      internalTxs,
      gasRefund,
      Duration.Zero,
      error,
      tracingError,
      accessedAddresses,
      accessedStorageKeys
    )
}
