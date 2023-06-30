package io.iohk.scevm.exec.vm

import cats.Show
import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Address, TransactionLogEntry, showForByteString}
import io.iohk.scevm.exec.vm.tracing.Tracer.TracingError

import scala.concurrent.duration.Duration

/** Representation of the result of execution of a contract
  *
  * @param returnData bytes returned by the executed contract (set by [[RETURN]] opcode)
  * @param gasRemaining amount of gas remaining after execution
  * @param world represents changes to the world state
  * @param addressesToDelete list of addresses of accounts scheduled to be deleted
  * @param logs transaction logs
  * @param internalTxs list of internal transactions (for debugging/tracing) if enabled in config
  * @param gasRefund the amount of gas to be refunded after execution
  * @param executionTime Amount of time the transaction took to execute
  * @param error defined when the program terminated abnormally
  * @param tracingError defined only when tracing is enabled and there is an error in the tracer (like invalid javascript)
  * @param accessedAddresses set of addresses that were accessed in this transaction (EIP-2929)
  * @param accessedStorageKeys set of storage slots that were accessed in this transaction (EIP-2929)
  */
final case class ProgramResult[W <: WorldState[W, S], S <: Storage[S]](
    returnData: ByteString,
    gasRemaining: BigInt,
    world: W,
    addressesToDelete: Set[Address],
    logs: Seq[TransactionLogEntry],
    internalTxs: Seq[InternalTransaction],
    gasRefund: BigInt,
    executionTime: Duration,
    error: Option[ProgramError],
    tracingError: Option[TracingError],
    accessedAddresses: Set[Address],
    accessedStorageKeys: Set[(Address, BigInt)]
) {
  def toEither: Either[ProgramResultError, ByteString] = error.map(ProgramResultError(_, returnData)).toLeft(returnData)
}

final case class ProgramResultError(error: ProgramError, data: ByteString)

object ProgramResultError {
  implicit val show: Show[ProgramResultError] = cats.derived.semiauto.show
}
