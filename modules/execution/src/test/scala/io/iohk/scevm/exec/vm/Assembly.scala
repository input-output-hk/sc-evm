package io.iohk.scevm.exec.vm

import io.iohk.bytes.ByteString
import io.iohk.scevm.exec.config.EvmConfig

object Assembly {

  sealed trait ByteCode {
    def bytes: ByteString
  }

  implicit final class OpCodeAsByteCode(val op: OpCode) extends ByteCode {
    def bytes: ByteString = ByteString(op.code)
  }

  implicit final class IntAsByteCode(val i: Int) extends ByteCode {
    def bytes: ByteString = ByteString(i.toByte)
  }

  implicit final class ByteAsByteCode(val byte: Byte) extends ByteCode {
    def bytes: ByteString = ByteString(byte)
  }

  implicit final class ByteStringAsByteCode(val bytes: ByteString) extends ByteCode
}

import Assembly._

final case class Assembly(byteCode: ByteCode*) {
  val code: ByteString = byteCode.foldLeft(ByteString.empty)(_.bytes ++ _.bytes)

  val program: Program = Program(code)

  def linearConstGas(config: EvmConfig): BigInt = byteCode.foldLeft(BigInt(0)) {
    case (g, b: OpCodeAsByteCode) => g + b.op.baseGasFn(config.feeSchedule)
    case (g, _)                   => g
  }
}
