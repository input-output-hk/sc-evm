package io.iohk.scevm.consensus.validators

import cats.implicits.showInterpolator
import io.iohk.scevm.consensus.validators.ChainingValidator._
import io.iohk.scevm.domain.ObftHeader

trait ChainingValidator {
  def validate(parent: ObftHeader, block: ObftHeader): Either[ChainingError, ObftHeader]
}

class ChainingValidatorImpl extends ChainingValidator {
  override def validate(parent: ObftHeader, block: ObftHeader): Either[ChainingError, ObftHeader] =
    for {
      _ <- validateParentHash(parent, block)
      _ <- validateNumber(parent, block)
      _ <- validateSlotNumber(parent, block)
      _ <- validateTimestamp(parent, block)
      _ <- validateGasLimit(parent, block)
    } yield block

  /* Private methods below */
  private def validateParentHash(parent: ObftHeader, block: ObftHeader): Either[ParentHashError, ObftHeader] =
    if (parent.hash == block.parentHash) Right(block)
    else Left(ParentHashError(parent, block))

  private def validateNumber(parent: ObftHeader, block: ObftHeader): Either[NumberError, ObftHeader] =
    if (parent.number.next == block.number) Right(block)
    else Left(NumberError(parent, block))

  private def validateSlotNumber(parent: ObftHeader, block: ObftHeader): Either[SlotNumberError, ObftHeader] =
    if (parent.slotNumber.number < block.slotNumber.number) Right(block)
    else Left(SlotNumberError(parent, block))

  private def validateTimestamp(parent: ObftHeader, block: ObftHeader): Either[TimestampError, ObftHeader] =
    if (parent.unixTimestamp < block.unixTimestamp) Right(block)
    else Left(TimestampError(parent, block))

  private def validateGasLimit(parent: ObftHeader, block: ObftHeader): Either[GasLimitError, ObftHeader] = {
    val gasLimitDiff      = (block.gasLimit - parent.gasLimit).abs
    val gasLimitDiffLimit = parent.gasLimit / GasLimitBoundDivisor - 1

    if (gasLimitDiff < gasLimitDiffLimit && MinGasLimit <= block.gasLimit) Right(block)
    else Left(GasLimitError(parent, block))
  }
}

object ChainingValidator {
  val GasLimitBoundDivisor: Int = 1024
  val MinGasLimit: BigInt       = 5000 //Although the paper states this value is 125000, on the different clients 5000 is used

  sealed trait ChainingError {
    def parent: ObftHeader
    def child: ObftHeader
  }

  final case class ParentHashError(parent: ObftHeader, child: ObftHeader) extends ChainingError {
    override def toString: String =
      show"ParentHashError(parent=$parent, child=$child, child.parent=${child.parentHash})"
  }

  final case class NumberError(parent: ObftHeader, child: ObftHeader) extends ChainingError {
    override def toString: String = show"NumberError(parent=$parent, child=$child)"
  }

  final case class SlotNumberError(parent: ObftHeader, child: ObftHeader) extends ChainingError {
    override def toString: String = show"SlotNumberError(parent=$parent, child=$child)"
  }

  final case class TimestampError(parent: ObftHeader, child: ObftHeader) extends ChainingError {
    override def toString: String =
      show"TimestampError(parent=$parent, parent.timestamp=${parent.unixTimestamp}, child=$child, child.timestamp=${child.unixTimestamp})"
  }

  final case class GasLimitError(parent: ObftHeader, child: ObftHeader) extends ChainingError {
    override def toString: String =
      show"GasLimitError(parent=$parent, parent.gasLimit=${parent.gasLimit}, child=$child, child.gasLimit=${child.gasLimit})"
  }

  def userFriendlyMessage(error: ChainingError): String = error match {
    case err: ParentHashError => s"The provided parent does not match the expected hash  ($err)"
    case err @ NumberError(parent, child) =>
      s"The provided parent's number ${parent.number} does match the expected number (${child.number} - 1) ($err)"
    case err: SlotNumberError => s"The parent slot number is not lower than the child's ($err)"
    case err: TimestampError  => s"The parent timestamp is not lower than the child's ($err)"
    case err: GasLimitError   => s"The child's gas limit is not within the expected bounds ($err)"
  }
}
