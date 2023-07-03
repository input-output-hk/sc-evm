package io.iohk.scevm.exec.vm

import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.kec256
import io.iohk.ethereum.utils.ByteStringUtils.Padding
import io.iohk.scevm.exec.config.EvmConfig

import scala.annotation.tailrec

/** Holds a program's code and provides utilities for accessing it (defaulting to zeroes when out of scope)
  *
  * @param code the EVM bytecode as bytes
  */
final case class Program(code: ByteString) {

  def getByte(pc: Int): Byte =
    code.lift(pc).getOrElse(0)

  def getBytes(from: Int, size: Int): ByteString =
    code.slice(from, from + size).padToByteString(size, 0.toByte)

  val length: Int = code.size

  lazy val validJumpDestinations: Set[Int] = validJumpDestinationsAfterPosition(0)

  /** Returns the valid jump destinations of the program after a given position
    * See section 9.4.3 in Yellow Paper for more detail.
    *
    * @param pos from where to start searching for valid jump destinations in the code.
    * @param accum with the previously obtained valid jump destinations.
    */
  @tailrec
  private def validJumpDestinationsAfterPosition(pos: Int, accum: Set[Int] = Set.empty): Set[Int] =
    if (pos < 0 || pos >= length) accum
    else {
      val byte = code(pos)
      val opCode = EvmConfig.FrontierOpCodes.byteToOpCode.get(
        byte
      ) // we only need to check PushOp and JUMPDEST, they are both present in Frontier
      opCode match {
        case Some(pushOp: PushOp) => validJumpDestinationsAfterPosition(pos + pushOp.i + 2, accum)
        case Some(JUMPDEST)       => validJumpDestinationsAfterPosition(pos + 1, accum + pos)
        case _                    => validJumpDestinationsAfterPosition(pos + 1, accum)
      }
    }

  lazy val codeHash: ByteString =
    kec256(code)

}
