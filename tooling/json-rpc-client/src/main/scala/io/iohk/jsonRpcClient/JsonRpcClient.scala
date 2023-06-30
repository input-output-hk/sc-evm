package io.iohk.jsonRpcClient

import cats._
import cats.effect.kernel.Resource
import cats.effect.{Async, std}
import cats.implicits._
import io.circe.generic.auto._
import io.circe.{Decoder, Json}
import sttp.client3._
import sttp.client3.circe._
import sttp.client3.httpclient.fs2.HttpClientFs2Backend
import sttp.model.Uri

object JsonRpcClient {
  def asResource[F[_]: Async: std.Console](uri: Uri, verbose: Boolean): Resource[F, JsonRpcClient[F]] =
    HttpClientFs2Backend
      .resource[F]()
      .map(backend => new JsonRpcClient[F](uri, backend, verbose))
}

class JsonRpcClient[F[_]: MonadThrow: std.Console](uri: Uri, backend: SttpBackend[F, Any], verbose: Boolean) {

  def callEndpoint[T: Decoder](method: String, params: List[Json]): F[T] =
    callEndpointPreserveJson(method, params).map(_._2)

  def callEndpointPreserveJson[T: Decoder](method: String, params: List[Json]): F[(Json, T)] = {
    val jsonRpcRequest = JsonRpcRequest(method, params)
    (for {
      _ <- Monad[F].whenA(verbose)(
             std.Console[F].println(s">>> Sending jsonrpc to: $uri") >> std.Console[F].println(jsonRpcRequest)
           )
      responseOrError <- basicRequest
                           .post(uri)
                           .body(jsonRpcRequest)
                           .response(asJson[Json])
                           .send(backend)
      _ <- Monad[F].whenA(verbose)(
             std.Console[F].println(s"<<< Got jsonrpc response:") >> std.Console[F].println(responseOrError.body.merge)
           )
      responseJson    <- MonadThrow[F].fromEither(responseOrError.body)
      decodedResponse <- MonadThrow[F].fromEither(responseJson.as[JsonRpcResponse[T]])
    } yield (responseJson, decodedResponse.result))
      .adaptError {
        case HttpError(body, statusCode) =>
          new Exception(s"Request to $method failed. Code: $statusCode. Body: '$body'.")
        case DeserializationException(body, error) =>
          new Exception(s"Deserialization of '$body' failed with: $error.")
      }
  }
}
