package io.iohk.ethereum.utils

import io.iohk.bytes.ByteString

import scala.util.Try

object Hex {

  /** Convert bytes to hexadecimal string without "0x" prefix */
  def toHexString(bytes: Iterable[Byte]): String =
    bytes.map("%02x".format(_)).mkString

  /** Parse hexadecimal string ignoring "0x" prefix */
  def decodeAsArrayUnsafe(hex: String): Array[Byte] = {
    val withoutPrefix = hex.replaceFirst("^0x", "")
    val normalized    = if (withoutPrefix.length % 2 == 1) "0" + withoutPrefix else withoutPrefix

    normalized
      .sliding(2, 2)
      .map(s => Integer.parseInt(s.mkString(""), 16).toByte)
      .toArray
  }

  /** Parse hexadecimal string ignoring "0x" prefix */
  def decodeUnsafe(hex: String): ByteString = ByteString(decodeAsArrayUnsafe(hex))

  /** Parse hexadecimal string ignoring "0x" prefix */
  def decode(hex: String): Either[String, ByteString] = Try(Hex.decodeUnsafe(hex)).toEither.left.map(_.getMessage)
  def parseHexNumberUnsafe(s: String): BigInt =
    BigInt(s.replaceFirst("^0x", ""), 16)
}
