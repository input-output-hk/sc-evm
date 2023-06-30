package io.iohk.scevm.extvm.statuses

import enumeratum.values.{IntEnum, IntEnumEntry}
import io.iohk.scevm.domain.Receipt.StatusCode
import io.iohk.scevm.domain.UInt256
import io.iohk.scevm.extvm.statuses.EVMStatus.Successful

import scala.collection.immutable

/** Data type representing status of EVM program execution.
  */
sealed abstract class EVMStatus(val value: Int) extends IntEnumEntry with VMStatus {

  override def isFailed: Boolean =
    this match {
      case Successful => false
      case _          => true
    }

  override def statusCode: StatusCode = if (isFailed) EVMStatusCodes.evmFailure else EVMStatusCodes.evmSuccess

}

object EVMStatus extends IntEnum[EVMStatus] {

  sealed abstract class EVMException(value: Int) extends EVMStatus(value)

  override def values: immutable.IndexedSeq[EVMStatus] = findValues

  // scalastyle:off magic.number
  /**  This is a type of "development error", it should never happened at production time.
    *  Because implies a bad configuration or a bug in the code, like missed a validation before operate.
    */
  case object OverflowOccurred extends EVMException(-1)

  case object Successful extends EVMStatus(0)
  case object Revert     extends EVMStatus(1)

  case object OutOfGas extends EVMException(2)

  final case class InvalidInstruction(code: Byte) extends EVMException(3)

  case object StackOverflow  extends EVMException(4)
  case object StackUnderflow extends EVMException(5)

  final case class InvalidJump(target: UInt256) extends EVMException(6)

  case object StaticModeViolation extends EVMException(7)
  case object InvalidCall         extends EVMException(8)
  case object ReturnDataOverflow  extends EVMException(9)

}
