package io.iohk.scevm.rpc.serialization

import io.iohk.armadillo.json.json4s.Json4sSupport
import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Address, BlockHash, BlockNumber, Gas, Nonce, Token}
import io.iohk.scevm.rpc.controllers.BlockParam
import io.iohk.scevm.rpc.domain.JsonRpcError._
import io.iohk.scevm.rpc.domain.{JsonRpcError, TransactionRequest}
import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST._
import org.json4s.{Formats, Serialization}

import java.time.Duration
import scala.util.Try

trait JsonMethodsImplicits {
  implicit val jsonSupport: Json4sSupport = ArmadilloJsonSupport.jsonSupport

  implicit val formats: Formats = JsonSerializers.formats

  implicit val serialization: Serialization = org.json4s.native.Serialization

  def convertToHex(input: BigInt): String = s"0x${input.toString(16)}"

  def encodeAsHex(input: ByteString): JString =
    JString(
      if (input.isEmpty) "0x" else s"0x${Hex.toHexString(input.toArray[Byte])}"
    )

  def encodeAsHex(input: Byte): JString = JString(s"0x${Hex.toHexString(Array(input))}")

  def encodeAsHex(input: BigInt): JString = JString(convertToHex(input))

  def decode(s: String): Array[Byte] = {
    val stripped   = s.replaceFirst("^0x", "")
    val normalized = if (stripped.length % 2 == 1) "0" + stripped else stripped
    Hex.decode(normalized)
  }

  protected def extractAddress(input: String): Either[JsonRpcError, Address] =
    Try(Address(input)).toEither.left.map(_ => JsonRpcError.InvalidAddress)

  def extractDurationQuantity(input: JValue): Either[JsonRpcError, Duration] = for {
    quantity <- extractQuantity(input)
    duration <- getDuration(quantity)
  } yield duration

  private def getDuration(value: BigInt): Either[JsonRpcError, Duration] =
    Either.cond(
      value.isValidInt,
      Duration.ofSeconds(value.toInt),
      JsonRpcError.InvalidParams("Duration should be a number of seconds, less than 2^31 - 1")
    )

  protected def extractAddress(input: JString): Either[JsonRpcError, Address] =
    extractAddress(input.s)

  def extractBytes(input: String): Either[JsonRpcError, ByteString] =
    Try(ByteString(decode(input))).toEither.left.map(_ => JsonRpcError.InvalidParams())

  protected def extractBytes(input: JString): Either[JsonRpcError, ByteString] =
    extractBytes(input.s)

  protected def extractBytes(input: String, size: Int): Either[JsonRpcError, ByteString] =
    extractBytes(input).filterOrElse(
      _.length == size,
      JsonRpcError.InvalidParams(s"Invalid value [$input], expected $size bytes")
    )

  protected def extractHash(input: String): Either[JsonRpcError, ByteString] =
    extractBytes(input, 32)

  protected def extractBlockHash(input: String): Either[JsonRpcError, BlockHash] =
    extractHash(input).map(BlockHash.apply)

  protected def extractBlockParam(input: JValue): Either[JsonRpcError, BlockParam] =
    input match {
      case JString("earliest") => Right(BlockParam.Earliest)
      case JString("latest")   => Right(BlockParam.Latest)
      case JString("pending")  => Right(BlockParam.Pending)
      case JString("stable")   => Right(BlockParam.Stable)
      case other =>
        extractQuantity(other)
          .map(n => BlockParam.ByNumber(BlockNumber(n)))
          .left
          .map(_ => JsonRpcError.InvalidParams(s"Invalid default block param: $other"))
    }

  def extractQuantity(input: JValue): Either[JsonRpcError, BigInt] =
    input match {
      case JInt(n) =>
        Right(n)

      case JString(s) =>
        Try(BigInt(1, decode(s))).toEither.left.map(_ => JsonRpcError.InvalidParams())

      case _ =>
        Left(JsonRpcError.InvalidParams("could not extract quantity"))
    }

  protected def extractBlockNumber(input: JValue): Either[JsonRpcError, BlockNumber] =
    input match {
      case JInt(n) =>
        Right(BlockNumber(n))

      case JString(s) =>
        Try(BigInt(1, decode(s))).toEither.map(BlockNumber.apply).left.map(_ => JsonRpcError.InvalidParams())

      case _ =>
        Left(JsonRpcError.InvalidParams(s"could not extract block number from $input"))
    }

  protected def optionalQuantity(input: JValue): Either[JsonRpcError, Option[BigInt]] =
    input match {
      case JNothing => Right(None)
      case o        => extractQuantity(o).map(Some(_))
    }

  protected def optionalBlockNumber(input: JValue): Either[JsonRpcError, Option[BlockNumber]] =
    input match {
      case JNothing => Right(None)
      case o        => extractBlockNumber(o).map(Some(_))
    }

  def extractTx(input: Map[String, JValue]): Either[JsonRpcError, TransactionRequest] = {
    def optionalQuantity(name: String): Either[JsonRpcError, Option[BigInt]] = input.get(name) match {
      case Some(v) => extractQuantity(v).map(Some(_))
      case None    => Right(None)
    }

    for {
      from <- input.get("from") match {
                case Some(JString(s)) if s.nonEmpty => extractAddress(s)
                case Some(_)                        => Left(InvalidAddress)
                case _                              => Left(InvalidParams("TX 'from' is required"))
              }

      to <- input.get("to") match {
              case Some(JString(s)) if s.nonEmpty => extractAddress(s).map(Option.apply)
              case Some(JString(_))               => extractAddress("0x0").map(Option.apply)
              case Some(_)                        => Left(InvalidAddress)
              case None                           => Right(None)
            }

      value <- optionalQuantity("value")

      gas <- optionalQuantity("gas")

      gasPrice <- optionalQuantity("gasPrice")

      nonce <- optionalQuantity("nonce")

      data <- input.get("data") match {
                case Some(JString(s)) => extractBytes(s).map(Some(_))
                case Some(_)          => Left(InvalidParams())
                case None             => Right(None)
              }
    } yield TransactionRequest(
      from,
      to,
      value.map(Token.apply),
      gas.map(Gas.apply),
      gasPrice.map(Token.apply),
      nonce.map(Nonce(_)),
      data
    )
  }

  def toEitherOpt[A, B](opt: Option[Either[A, B]]): Either[A, Option[B]] =
    opt.map(_.map(Some.apply)).getOrElse(Right(None))

}

object JsonMethodsImplicits extends JsonMethodsImplicits
