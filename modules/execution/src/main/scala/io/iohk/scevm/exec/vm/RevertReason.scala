package io.iohk.scevm.exec.vm

import io.iohk.ethereum.crypto.kec256
import io.iohk.scevm.domain.UInt256

import java.nio.charset.StandardCharsets

/** Decode abi-encoded revert reason to human-readable string
  * https://docs.soliditylang.org/en/v0.8.11/control-structures.html#revert
  */
object RevertReason {

  /** Example:
    * 0x08c379a0                                                         // Function selector for Error(string)
    * 0x0000000000000000000000000000000000000000000000000000000000000020 // Data offset
    * 0x000000000000000000000000000000000000000000000000000000000000001a // Message length
    * 0x4e6f7420656e6f7567682045746865722070726f76696465642e000000000000 // Message data
    */

  private val functionSelectorLength = 4
  private val wordLength             = 32
  private val errorFunctionSelector  = kec256("Error(string)".getBytes).take(4)

  def decode(data: Array[Byte]): String = {
    val messageLength = UInt256(
      data.slice(functionSelectorLength + wordLength, functionSelectorLength + wordLength * 2)
    )
    val messageData =
      data.slice(functionSelectorLength + wordLength * 2, functionSelectorLength + wordLength * 2 + messageLength.toInt)
    new String(messageData, StandardCharsets.UTF_8)
  }

  def encode(reason: String): Array[Byte] = {
    val dataOffset    = UInt256(wordLength).bytes.toArray
    val messageData   = reason.getBytes(StandardCharsets.UTF_8)
    val messageLength = UInt256(messageData.length).bytes.toArray
    val padding = messageData.length % wordLength match {
      case 0 => Array.emptyByteArray
      case n => Array.fill(wordLength - n)(0.toByte)
    }
    val data = errorFunctionSelector ++ dataOffset ++ messageLength ++ messageData ++ padding
    data
  }

}
