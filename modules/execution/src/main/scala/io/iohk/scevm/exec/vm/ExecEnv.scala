package io.iohk.scevm.exec.vm

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Address, BlockContext, UInt256}
import io.iohk.scevm.exec.config.EvmConfig

object ExecEnv {
  def apply(context: ProgramContext[_, _], code: ByteString, ownerAddr: Address): ExecEnv = {
    import context._

    ExecEnv(
      ownerAddr,
      callerAddr,
      originAddr,
      maxPriorityFeePerGas,
      inputData,
      value,
      Program(code),
      blockContext,
      callDepth,
      startGas,
      evmConfig
    )
  }
}

// TODO: delete me
// TODO check comment about deletion
/** Execution environment constants of an EVM program.
  *  See section 9.3 in Yellow Paper for more detail.
  *  @param ownerAddr             I_a: address of the account that owns the code
  *  @param callerAddr            I_s: address of the account which caused the code to be executing
  *  @param originAddr            I_o: sender address of the transaction that originated this execution
  *  @param priorityFeePerGas     maximum fee per gas to give to miners to incentivize them to include transactions
  *  @param inputData             I_d
  *  @param value                 I_v
  *  @param program               I_b
  *  @param blockContext           I_H
  *  @param callDepth             I_e
  *  Extra:
  *  @param startGas              gas provided for execution
  *  @param evmConfig             EVM configuration (forks)
  */
final case class ExecEnv(
    ownerAddr: Address,
    callerAddr: Address,
    originAddr: Address,
    priorityFeePerGas: UInt256,
    inputData: ByteString,
    value: UInt256,
    program: Program,
    blockContext: BlockContext,
    callDepth: Int,
    startGas: BigInt,
    evmConfig: EvmConfig
)
