package io.iohk.scevm.solidity

import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.kec256
import io.iohk.scevm.domain.UInt256

import java.nio.charset.StandardCharsets

/** Container for solidity ABI related functions.
  *
  * Currently it only allows to encode a solidity call.
  * @see https://docs.soliditylang.org/en/latest/abi-spec.html
  */
object SolidityAbi {

  private[solidity] val WordSize = UInt256.Size

  /** Encodes a solidity call into a ByteString to send as the payload of a transaction. */
  def solidityCall(functionName: String): ByteString =
    functionSelector0(functionName)

  /** Encodes a solidity call into a ByteString to send as the payload of a transaction. */
  def solidityCall[Arg1: SolidityAbiEncoder](functionName: String, arg1: Arg1): ByteString =
    functionSelector[Arg1](functionName) ++ implicitly[SolidityAbiEncoder[Tuple1[Arg1]]].encode(Tuple1(arg1))

  /** Encodes a solidity call into a ByteString to send as the payload of a transaction. */
  def solidityCall[Arg1: SolidityAbiEncoder, Arg2: SolidityAbiEncoder](
      functionName: String,
      arg1: Arg1,
      arg2: Arg2
  ): ByteString =
    functionSelector[Arg1, Arg2](functionName) ++ implicitly[SolidityAbiEncoder[(Arg1, Arg2)]].encode((arg1, arg2))

  /** Encodes a solidity call into a ByteString to send as the payload of a transaction. */
  def solidityCall[Arg1: SolidityAbiEncoder, Arg2: SolidityAbiEncoder, Arg3: SolidityAbiEncoder](
      functionName: String,
      arg1: Arg1,
      arg2: Arg2,
      arg3: Arg3
  ): ByteString =
    functionSelector[Arg1, Arg2, Arg3](functionName) ++ implicitly[SolidityAbiEncoder[(Arg1, Arg2, Arg3)]]
      .encode((arg1, arg2, arg3))

  def functionSelector0(functionName: String): ByteString = {
    val selectorString = functionName + "()"
    ByteString(kec256(selectorString.getBytes(StandardCharsets.UTF_8)).take(4))
  }

  def functionSelector[Arg1: SolidityAbiEncoder](functionName: String): ByteString = {
    val selectorString = functionName + SolidityAbiEncoder[Tuple1[Arg1]].underlyingType.name
    ByteString(kec256(selectorString.getBytes(StandardCharsets.UTF_8)).take(4))
  }

  def functionSelector[Arg1: SolidityAbiEncoder, Arg2: SolidityAbiEncoder](functionName: String): ByteString = {
    val selectorString = functionName + SolidityAbiEncoder[(Arg1, Arg2)].underlyingType.name
    ByteString(kec256(selectorString.getBytes(StandardCharsets.UTF_8)).take(4))
  }

  def functionSelector[Arg1: SolidityAbiEncoder, Arg2: SolidityAbiEncoder, Arg3: SolidityAbiEncoder](
      functionName: String
  ): ByteString = {
    val selectorString = functionName + SolidityAbiEncoder[(Arg1, Arg2, Arg3)].underlyingType.name
    ByteString(kec256(selectorString.getBytes(StandardCharsets.UTF_8)).take(4))
  }

  def defaultEventTopic[Arg1: SolidityAbiEncoder, Arg2: SolidityAbiEncoder, Arg3: SolidityAbiEncoder](
      eventName: String
  ): ByteString = {
    val selectorString = eventName + SolidityAbiEncoder[(Arg1, Arg2, Arg3)].underlyingType.name
    ByteString(kec256(selectorString.getBytes(StandardCharsets.UTF_8)))
  }

  def defaultEventTopic[
      Arg1: SolidityAbiEncoder,
      Arg2: SolidityAbiEncoder,
      Arg3: SolidityAbiEncoder,
      Arg4: SolidityAbiEncoder
  ](
      eventName: String
  ): ByteString = {
    val selectorString = eventName + SolidityAbiEncoder[(Arg1, Arg2, Arg3, Arg4)].underlyingType.name
    ByteString(kec256(selectorString.getBytes(StandardCharsets.UTF_8)))
  }
}
