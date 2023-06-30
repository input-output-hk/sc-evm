package io.iohk.scevm.rpc.domain

import io.iohk.scevm.domain.TransactionHash
import io.iohk.scevm.rpc.domain.JsonRpcError.EthCustomError.Unauthorized
import io.iohk.scevm.rpc.serialization.{JsonEncoder, JsonMethodsImplicits}
import org.json4s.{JInt, JLong, JObject, JString, JValue}

final case class JsonRpcError(code: Int, message: String, data: Option[JValue])

// scalastyle:off magic.number
// scalastyle:off public.methods.have.type
object JsonRpcError extends JsonMethodsImplicits {

  def from[T: JsonEncoder](code: Int, message: String, data: T): JsonRpcError =
    JsonRpcError(code, message, Some(JsonEncoder[T].encodeJson(data)))

  implicit val rateLimitInformation: JsonEncoder[RateLimitInformation] = (rateLimit: RateLimitInformation) =>
    JObject(
      "backoff_seconds" -> JLong(rateLimit.backoffSeconds)
    )

  implicit val jsonRpcErrorEncoder: JsonEncoder[JsonRpcError] = err =>
    JObject(
      List("code" -> JsonEncoder.encode(err.code), "message" -> JsonEncoder.encode(err.message)) ++ err.data.map(
        "data"    -> _
      )
    )

  final case class RateLimitInformation(backoffSeconds: Long)
  def RateLimitError(backoffSeconds: Long): JsonRpcError =
    JsonRpcError.from(
      JsonRpcServerErrorCodes.TooManyRequests,
      "request rate exceeded",
      RateLimitInformation(backoffSeconds)
    )
  val ParseError: JsonRpcError =
    JsonRpcError(
      JsonRpcStandardErrorCodes.ParseError,
      "An error occurred on the server while parsing the JSON text",
      None
    )
  val InvalidRequest: JsonRpcError =
    JsonRpcError(JsonRpcStandardErrorCodes.InvalidRequest, "The JSON sent is not a valid Request object", None)
  val MethodNotFound: JsonRpcError =
    JsonRpcError(JsonRpcStandardErrorCodes.MethodNotFound, "The method does not exist / is not available", None)
  def InvalidParams(msg: String = "Invalid method parameters"): JsonRpcError =
    JsonRpcError(JsonRpcStandardErrorCodes.InvalidParams, msg, None)
  val InternalError: JsonRpcError =
    JsonRpcError(JsonRpcStandardErrorCodes.InternalError, "Internal JSON-RPC error", None)

  def TracerException(msg: String): JsonRpcError = JsonRpcError(400, msg, None)
  def TransactionNotFound(txHash: TransactionHash): JsonRpcError =
    JsonRpcError(404, s"Transaction or sender of transaction not found for hash ${txHash.toHex}", None)

  def LogicError(msg: String): JsonRpcError  = JsonRpcError(-32000, msg, None)
  def ServerError(msg: String): JsonRpcError = JsonRpcError(JsonRpcServerErrorCodes.ServerError, msg, None)
  val InvalidAddress: JsonRpcError           = InvalidParams("Invalid address, expected 20 bytes (40 hex digits)")
  val AccountLocked: Unauthorized            = Unauthorized("account is locked or unknown")

  def executionError(reasons: EthCustomError*): JsonRpcError =
    JsonRpcError.from(3, "Execution error", reasons.toList)
  val NodeNotFound: JsonRpcError        = executionError(EthCustomError.DoesntExist("State node"))
  val BlockNotFound: JsonRpcError       = executionError(EthCustomError.DoesntExist("Block"))
  val ReceiptNotAvailable: JsonRpcError = executionError(EthCustomError.DoesntExist("Receipt"))

  val InvalidKey: JsonRpcError        = InvalidParams("Invalid key provided, expected 32 bytes (64 hex digits)")
  val InvalidPassphrase: JsonRpcError = LogicError("Could not decrypt key with given passphrase")
  val KeyNotFound: JsonRpcError       = LogicError("No key found for the given address")
  val PassphraseTooShort: Int => JsonRpcError = minLength =>
    LogicError(s"Provided passphrase must have at least $minLength characters")

  val SenderNotFound: TransactionHash => JsonRpcError = hash =>
    LogicError(s"Could not find the sender of the transaction with hash ${hash.toHex}")

  // Custom errors based on proposal https://eth.wiki/json-rpc/json-rpc-error-codes-improvement-proposal
  sealed abstract class EthCustomError private (val code: Int, val message: String)
  object EthCustomError {
    implicit val ethCustomErrorEncoder: JsonEncoder[EthCustomError] = err =>
      JObject("code" -> JInt(err.code), "message" -> JString(err.message))

    final case class Unauthorized(override val message: String) extends EthCustomError(code = 1, message)
    final case class DoesntExist(what: String)                  extends EthCustomError(100, s"$what doesn't exist")
    case object RequiresEther                                   extends EthCustomError(101, "Requires ether")
    case object GasTooLow                                       extends EthCustomError(102, "Gas too low")
    case object GasLimitExceeded                                extends EthCustomError(103, "Gas limit exceeded")
    case object Rejected                                        extends EthCustomError(104, "Rejected")
    case object EtherTooLow                                     extends EthCustomError(105, "Ether too low")
  }

  final case class JsonRpcErrorThrowable(error: JsonRpcError) extends Throwable
}

object JsonRpcServerErrorCodes {
  val ServerError     = -32000
  val TooManyRequests = -32005
}

object JsonRpcStandardErrorCodes {
  // see https://www.jsonrpc.org/specification#error_object

  val ParseError: Int     = -32700
  val InvalidRequest: Int = -32600
  val MethodNotFound: Int = -32601
  val InvalidParams: Int  = -32602
  val InternalError: Int  = -32603
}
