package io.iohk.scevm.exec.vm

import cats.effect.IO
import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Address, UInt256}
import io.iohk.scevm.exec.config.EvmConfig
import io.iohk.scevm.exec.vm.tracing.Tracer.TracingError
import io.iohk.scevm.exec.vm.tracing.{NoopTracer, Tracer}
import io.iohk.scevm.utils.Logger

import scala.annotation.tailrec
import scala.concurrent.duration.{Duration, NANOSECONDS}

class EVM[W <: WorldState[W, S], S <: Storage[S]] private (tracer: Tracer) extends VM[IO, W, S] with Logger {

  /** Executes a top-level program (transaction)
    * @param context context to be executed
    * @return result of the execution
    */
  override def run(context: PC): IO[ProgramResult[W, S]] = {
    import context._
    import org.bouncycastle.util.encoders.Hex

    log.trace(
      s"caller:  $callerAddr | recipient: $recipientAddr | maxPriorityFeePerGas: $maxPriorityFeePerGas |" +
        s" value: $value | inputData: ${Hex.toHexString(inputData.toArray)}"
    )

    tracer.init(context) match {
      case Right(_) =>
        for {
          startTime <- IO.delay(System.nanoTime())
          pgResult = context.recipientAddr match {
                       case Some(recipientAddr) => call(context, recipientAddr)
                       case None                => create(context)._1
                     }
          deltaTime = math.max(0, System.nanoTime() - startTime)
        } yield pgResult.copy(executionTime = Duration(deltaTime, NANOSECONDS))
      case Left(exception) =>
        IO.delay(EVM.tracingInitErrorResult(context, TracingError(exception)))
          .map(_.copy(executionTime = Duration.Zero))
    }
  }

  /** Message call - Θ function in YP
    */
  private def call(context: PC, ownerAddr: Address): PR =
    if (!EVM.isValidCall(context))
      EVM.invalidCallResult(context, Set.empty, Set.empty)
    else {
      require(context.recipientAddr.isDefined, "Recipient address must be defined for message call")
      executeCall(context, ownerAddr)
    }

  private[vm] def callWithTrace(context: PC, ownerAddr: Address): PR =
    if (!EVM.isValidCall(context))
      EVM.invalidCallResult(context, Set.empty, Set.empty)
    else {
      require(context.recipientAddr.isDefined, "Recipient address must be defined for message call")

      tracer.enter(context) match {
        case Right(_) =>
          val programResult = executeCall(context, ownerAddr)
          tracer.exit(context, programResult) match {
            case Right(_)        => programResult
            case Left(exception) => EVM.invalidTraceCallResult(context, TracingError(exception))
          }

        case Left(exception) => EVM.invalidTraceCallResult(context, TracingError(exception))
      }
    }

  private def executeCall(context: PC, ownerAddr: Address): PR = {
    val (preparedCtx, codeToExecute) = EVM.prepareCall(context)

    if (PrecompiledContracts.isDefinedAt(preparedCtx))
      PrecompiledContracts.run(preparedCtx)
    else {

      val env = ExecEnv(preparedCtx, codeToExecute, ownerAddr)

      val initialState: PS = ProgramState(this.callWithTrace, this.createWithTrace, this.selfDestruct, preparedCtx, env)
      exec(initialState).toResult
    }
  }

  /** Contract creation - Λ function in YP
    * salt is used to create contract by CREATE2 opcode. See https://github.com/ethereum/EIPs/blob/master/EIPS/eip-1014.md
    */
  private def create(context: PC, salt: Option[UInt256] = None): (PR, Address) =
    if (!EVM.isValidCall(context))
      EVM.invalidCreateResult(context, Set.empty, Set.empty)
    else {
      require(context.recipientAddr.isEmpty, "recipient address must be empty for contract creation")
      require(context.doTransfer, "contract creation will always transfer funds")

      executeCreate(context, salt)
    }

  private[vm] def createWithTrace(context: PC, salt: Option[UInt256] = None): (PR, Address) =
    if (!EVM.isValidCall(context))
      EVM.invalidCreateResult(context, Set.empty, Set.empty)
    else {
      require(context.recipientAddr.isEmpty, "recipient address must be empty for contract creation")
      require(context.doTransfer, "contract creation will always transfer funds")

      tracer.enter(context) match {
        case Right(_) =>
          val (programResult, newAddress) = executeCreate(context, salt)
          tracer.exit(context, programResult) match {
            case Right(_)        => (programResult, newAddress)
            case Left(exception) => EVM.invalidTraceCreateResult(context, TracingError(exception))
          }

        case Left(exception) => EVM.invalidTraceCreateResult(context, TracingError(exception))
      }
    }

  private def executeCreate(context: PC, salt: Option[UInt256] = None): (PR, Address) = {
    val (preparedCtx, codeToExecute, newAddress) = EVM.prepareCreate(context, salt)
    val env                                      = ExecEnv(context, codeToExecute, newAddress).copy(inputData = ByteString.empty)

    val initialState: PS =
      ProgramState(
        this.callWithTrace,
        this.createWithTrace,
        this.selfDestruct,
        preparedCtx,
        env
      )
        .addAccessedAddress(newAddress)

    val execResult = exec(initialState).toResult

    val newContractResult = EVM.saveNewContract(context, newAddress, execResult, env.evmConfig)
    (newContractResult, newAddress)
  }

  private[vm] def selfDestruct(context: PC, state: ProgramState[W, S]): ProgramState[W, S] =
    tracer.enter(context) match {
      case Right(_) =>
        val newState = executeSelfDestruct(state)
        tracer.exit(context, newState.toResult)
        newState
      case Left(_) => state
    }

  private def executeSelfDestruct(state: ProgramState[W, S]) = {
    val (refund, stack1)    = state.stack.pop
    val refundAddr: Address = Address(refund)
    val gasRefund: BigInt =
      if (state.addressesToDelete contains state.ownAddress) 0 else state.config.feeSchedule.R_selfdestruct

    val world =
      if (state.ownAddress == refundAddr) state.world.removeAllEther(state.ownAddress)
      else state.world.transfer(state.ownAddress, refundAddr, state.ownBalance)

    state
      .withWorld(world)
      .refundGas(gasRefund)
      .withAddressToDelete(state.ownAddress)
      .addAccessedAddress(refundAddr)
      .withStack(stack1)
      .withReturnData(ByteString.empty)
      .halt
  }

  @tailrec
  final private def exec(state: ProgramState[W, S]): ProgramState[W, S] = {
    val byte = state.program.getByte(state.pc)
    state.config.byteToOpCode.get(byte) match {
      case Some(opCode) =>
        tracer.step(opCode, state) match {
          case Right(_) =>
            val newState = opCode.execute(state)
            import newState._
            log.trace(
              s"$opCode | pc: $pc | depth: ${env.callDepth} | gasUsed: ${state.gas - gas} | gas remaining: $gas | stack: $stack"
            )

            if (newState.halted) {
              if (newState.error.isDefined) {
                tracer.fault(opCode, newState) match {
                  case Right(_)        => newState
                  case Left(exception) => newState.withTracingError(TracingError(exception))
                }
              } else {
                newState
              }
            } else {
              exec(newState)
            }
          case Left(exception) => state.withTracingError(TracingError(exception))
        }

      case None => state.withError(InvalidOpCode(byte)).halt
    }
  }

}

object EVM {
  def apply[W <: WorldState[W, S], S <: Storage[S]](): EVM[W, S]               = new EVM[W, S](new NoopTracer())
  def apply[W <: WorldState[W, S], S <: Storage[S]](tracer: Tracer): EVM[W, S] = new EVM[W, S](tracer)

  def prepareCall[W <: WorldState[W, S], S <: Storage[S]](
      context: ProgramContext[W, S]
  ): (ProgramContext[W, S], ByteString) = {
    def makeTransfer = context.world.transfer(context.callerAddr, context.recipientAddr.get, context.endowment)

    val world1 = if (context.doTransfer) makeTransfer else context.world
    val code   = world1.getCode(context.recipientAddr.get)
    (context.copy(world = world1), code)
  }

  def prepareCreate[W <: WorldState[W, S], S <: Storage[S]](
      context: ProgramContext[W, S],
      salt: Option[UInt256] = None
  ): (ProgramContext[W, S], ByteString, Address) = {
    val newAddress = salt
      .map(s => context.world.create2Address(context.callerAddr, s, context.inputData))
      .getOrElse(context.world.createAddress(context.callerAddr))

    // EIP-684
    // Need to check for conflicts before initialising account (initialisation set account codehash and storage root
    // to empty values.
    val conflict = context.world.nonEmptyCodeOrNonceAccount(newAddress)

    /** Specification of https://eips.ethereum.org/EIPS/eip-1283 states, that `originalValue` should be taken from
      * world which is left after `a reversion happens on the current transaction`, so in current scope `context.originalWorld`.
      *
      * But ets test expects that it should be taken from world after the new account initialisation, which clears
      * account storage.
      * As it seems other implementations encountered similar problems with this ambiguity:
      * ambiguity:
      * https://gist.github.com/holiman/0154f00d5fcec5f89e85894cbb46fcb2 - explanation of geth and parity treating this
      * situation differently.
      * https://github.com/mana-ethereum/mana/pull/579 - elixir eth client dealing with same problem.
      */
    val originInitialisedAccount = context.originalWorld.initialiseAccount(newAddress)
    val world1: W =
      context.world.initialiseAccount(newAddress).transfer(context.callerAddr, newAddress, context.endowment)
    val code = if (conflict) ByteString(INVALID.code) else context.inputData

    (
      context.copy(world = world1, originalWorld = originInitialisedAccount),
      code,
      newAddress
    )
  }

  def saveNewContract[W <: WorldState[W, S], S <: Storage[S]](
      context: ProgramContext[W, S],
      address: Address,
      result: ProgramResult[W, S],
      config: EvmConfig
  ): ProgramResult[W, S] =
    if (result.error.isDefined) {
      if (result.error.contains(RevertOccurs)) result else result.copy(gasRemaining = 0)
    } else {
      val contractCode          = result.returnData
      val startsWithInvalidByte = contractStartsWithInvalidByte(contractCode)

      if (startsWithInvalidByte) {
        result.copy(error = Some(InvalidCall), gasRemaining = 0)
      } else {
        val codeDepositCost     = config.calcCodeDepositCost(contractCode)
        val maxCodeSizeExceeded = exceedsMaxContractSize(context, config, contractCode)
        val codeStoreOutOfGas   = result.gasRemaining < codeDepositCost

        if (maxCodeSizeExceeded || (codeStoreOutOfGas && config.exceptionalFailedCodeDeposit)) {
          // Code size too big or code storage causes out-of-gas with exceptionalFailedCodeDeposit enabled
          result.copy(error = Some(OutOfGas), gasRemaining = 0)
        } else if (codeStoreOutOfGas && !config.exceptionalFailedCodeDeposit) {
          // Code storage causes out-of-gas with exceptionalFailedCodeDeposit disabled
          result
        } else { // Code storage succeeded
          result.copy(
            gasRemaining = result.gasRemaining - codeDepositCost,
            world = result.world.saveCode(address, result.returnData)
          )
        }
      }
    }

  // EIP-3541 Reject new contract code starting with the 0xEF byte
  private def contractStartsWithInvalidByte(contractCode: ByteString): Boolean =
    contractCode.headOption.contains(0xef.toByte)

  private def exceedsMaxContractSize[W <: WorldState[W, S], S <: Storage[S]](
      context: ProgramContext[W, S],
      config: EvmConfig,
      contractCode: ByteString
  ): Boolean = {
    lazy val maxCodeSizeExceeded = config.maxCodeSize.exists(codeSizeLimit => contractCode.size > codeSizeLimit)
    val currentBlock             = context.blockContext.number
    // Max code size was enabled on eip161 block number on eth network
    (currentBlock >= config.blockchainConfig.spuriousDragonBlockNumber) && maxCodeSizeExceeded
  }

  def isValidCall[W <: WorldState[W, S], S <: Storage[S]](context: ProgramContext[W, S]): Boolean =
    context.endowment <= context.world.getBalance(context.callerAddr) &&
      context.callDepth <= EvmConfig.MaxCallDepth

  def invalidCallResult[W <: WorldState[W, S], S <: Storage[S]](
      context: ProgramContext[W, S],
      accessedAddresses: Set[Address],
      accessedStorageKeys: Set[(Address, BigInt)]
  ): ProgramResult[W, S] =
    ProgramResult(
      ByteString.empty,
      context.startGas,
      context.world,
      Set(),
      Nil,
      Nil,
      0,
      Duration.Zero,
      Some(InvalidCall),
      None,
      accessedAddresses,
      accessedStorageKeys
    )

  private def invalidTraceCallResult[W <: WorldState[W, S], S <: Storage[S]](
      context: ProgramContext[W, S],
      tracingError: TracingError
  ): ProgramResult[W, S] =
    ProgramResult(
      ByteString.empty,
      context.startGas,
      context.world,
      Set(),
      Nil,
      Nil,
      0,
      Duration.Zero,
      None,
      Some(tracingError),
      Set.empty,
      Set.empty
    )

  private def invalidCreateResult[W <: WorldState[W, S], S <: Storage[S]](
      context: ProgramContext[W, S],
      accessedAddresses: Set[Address],
      accessedStorageKeys: Set[(Address, BigInt)]
  ): (ProgramResult[W, S], Address) = (invalidCallResult(context, accessedAddresses, accessedStorageKeys), Address(0))

  private def invalidTraceCreateResult[W <: WorldState[W, S], S <: Storage[S]](
      context: ProgramContext[W, S],
      tracingError: TracingError
  ): (ProgramResult[W, S], Address) = (invalidTraceCallResult(context, tracingError), Address(0))

  private def tracingInitErrorResult[W <: WorldState[W, S], S <: Storage[S]](
      context: ProgramContext[W, S],
      tracingError: TracingError
  ): ProgramResult[W, S] =
    ProgramResult[W, S](
      ByteString.empty,
      context.startGas,
      context.world,
      Set(),
      Nil,
      Nil,
      0,
      Duration.Zero,
      None,
      Some(tracingError),
      Set.empty,
      Set.empty
    )

}
