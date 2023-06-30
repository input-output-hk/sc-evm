package io.iohk.scevm.observer

import cats.MonadThrow
import cats.syntax.all._
import io.circe.generic.semiauto._
import io.circe.literal.JsonStringContext
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.Address
import sttp.client3._
import sttp.client3.circe._
import sttp.model.Uri

import ScEvmRpcClient._

class ScEvmRpcClient[F[_]: MonadThrow](uri: Uri, backend: SttpBackend[F, Any]) {

  def getStatus: F[GetStatusResponse] = basicRequest
    .post(uri)
    .body(JsonRpcRequest("sidechain_getStatus", List.empty))
    .response(asJson[JsonRpcResponse[GetStatusResponse]])
    .send(backend)
    .map(r => r.body.map(_.result))
    .flatMap(r => MonadThrow[F].fromEither(r))

  def getBalance(address: Address, blockNumberAsHex: String): F[BigInt] = basicRequest
    .post(uri)
    .body(
      JsonRpcRequest("eth_getBalance", List(address.asJson, blockNumberAsHex.asJson))
    )
    .response(asJson[JsonRpcResponse[String]])
    .send(backend)
    .map(response => response.body.map(jsonRpcResponse => Hex.parseHexNumberUnsafe(jsonRpcResponse.result)))
    .flatMap(r => MonadThrow[F].fromEither(r))
}

object ScEvmRpcClient {

  object JsonRpcRequest {
    def apply(method: String, params: List[io.circe.Json]): Json =
      json"""
      {
        "jsonrpc": "2.0",
        "method": $method,
        "params": $params,
        "id": 1
      }
    """
  }
  final case class JsonRpcResponse[T](jsonrpc: String, result: T, id: Int)
  object JsonRpcResponse {
    implicit def decoder[T: Decoder]: Decoder[JsonRpcResponse[T]] = io.circe.generic.semiauto.deriveDecoder
  }

  implicit val addressEncoder: Encoder[Address] = Encoder[String].contramap(addr => Hex.toHexString(addr.bytes))

  implicit val mainchainBlockDataDecoder: Decoder[MainchainBlockData] = deriveDecoder
  implicit val sidechainBlockDataDecoder: Decoder[SidechainBlockData] = deriveDecoder

  implicit val sidechainDataDecoder: Decoder[SidechainData] = deriveDecoder
  implicit val mainchainDataDecoder: Decoder[MainchainData] = deriveDecoder
  implicit val getStatusDecoder: Decoder[GetStatusResponse] = deriveDecoder

  final case class GetStatusResponse(
      sidechain: SidechainData,
      mainchain: MainchainData
  )

  final case class SidechainData(
      bestBlock: SidechainBlockData,
      stableBlock: SidechainBlockData,
      epoch: Long,
      epochPhase: String,
      slot: String,
      nextEpochTimestamp: String
  )

  final case class SidechainBlockData(
      number: String,
      hash: String,
      timestamp: String
  )

  final case class MainchainData(
      bestBlock: MainchainBlockData,
      stableBlock: MainchainBlockData,
      epoch: Long,
      slot: Long,
      nextEpochTimestamp: String
  )

  final case class MainchainBlockData(
      number: Long,
      hash: String,
      timestamp: String
  )

}
