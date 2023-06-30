package io.iohk.scevm.extvm.statuses

import enumeratum.values.{IntEnum, IntEnumEntry}
import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.Receipt.StatusCode
import io.iohk.scevm.extvm.statuses.EVMCStatus.Successful

import scala.collection.immutable

/** Data type representing status code of program execution.
  *
  * Defined according to the EVMC standard: https://github.com/ethereum/evmc
  *
  * Definition page: https://evmc.ethereum.org/group__EVMC.html#ga4c0be97f333c050ff45321fcaa34d920
  *
  * @param value Status code
  */
sealed abstract class EVMCStatus(val value: Int) extends IntEnumEntry with VMStatus {

  override def isFailed: Boolean =
    this match {
      case Successful => false
      case _          => true
    }

  override def statusCode: StatusCode = if (isFailed) EVMStatusCodes.evmFailure else EVMStatusCodes.evmSuccess

}

object EVMCStatus extends IntEnum[EVMCStatus] {

  def values: immutable.IndexedSeq[EVMCStatus] = findValues

  // scalastyle:off magic.number
  case object OutOfMemory                extends EVMCStatus(-3)
  case object Rejected                   extends EVMCStatus(-2)
  case object InternalError              extends EVMCStatus(-1)
  case object Successful                 extends EVMCStatus(0)
  case object Failed                     extends EVMCStatus(1)
  case object Revert                     extends EVMCStatus(2)
  case object OutOfGas                   extends EVMCStatus(3)
  case object InvalidInstruction         extends EVMCStatus(4)
  case object UndefinedInstruction       extends EVMCStatus(5)
  case object StackOverflow              extends EVMCStatus(6)
  case object StackUnderflow             extends EVMCStatus(7)
  case object InvalidJump                extends EVMCStatus(8)
  case object InvalidMemoryAccess        extends EVMCStatus(9)
  case object CallDepthExceeded          extends EVMCStatus(10)
  case object StaticModeViolation        extends EVMCStatus(11)
  case object PrecompileFailure          extends EVMCStatus(12)
  case object ContractValidationFailure  extends EVMCStatus(13)
  case object ArgumentOutOfRange         extends EVMCStatus(14)
  case object WASMUnreachableInstruction extends EVMCStatus(15)
  case object WASMTrap                   extends EVMCStatus(16)
  case object InsufficientBalance        extends EVMCStatus(17)

}

object EVMStatusCodes {
  def evmSuccess: StatusCode = StatusCode(ByteString(1))
  def evmFailure: StatusCode = StatusCode(ByteString(0))
}
