package io.iohk.sidechains.relay

import cats.effect.IO
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.{Decoder, Json}
import io.iohk.sidechains.relay.RpcFormats.{CommitteeResponse, EpochToUpload, GetSignaturesResponse, StatusPartial}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import sttp.client3.circe._
import sttp.client3.{HttpClientSyncBackend, _}
import sttp.model.Uri

trait RpcClient {
  def call[T: Decoder](endpoint: String, parameters: List[Json]): IO[T]
}

object RpcClient {
  def apply(uri: Uri): RpcClient = new RpcClient {
    private val backend          = HttpClientSyncBackend()
    implicit val log: Logger[IO] = Slf4jLogger.getLogger[IO]

    override def call[T: Decoder](method: String, params: List[Json]): IO[T] =
      log.info(s"Calling RPC '$method' with params $params at $uri") *>
        IO {
          val body =
            Json.obj(
              "jsonrpc" -> "2.0".asJson,
              "method"  -> method.asJson,
              "params"  -> params.asJson,
              "id"      -> 1.asJson
            )
          val result = basicRequest.body(body).post(uri).response(asJson[JsonRpcResponse[T]]).send(backend)
          result.body.left
            .map {
              case HttpError(body, statusCode)           => s"Request to $method failed. Code: $statusCode. Body: '$body'."
              case DeserializationException(body, error) => s"Deserialization of '$body' failed with: $error."
            }
            .map(_.result)
        }.flatTap(result => log.info(s"RPC result: $result"))
          .flatMap {
            case Right(result) => IO.pure(result)
            case Left(message) => IO.raiseError(new Exception(s"RpcClient failed: $message"))
          }
  }

  final case class JsonRpcResponse[T](jsonrpc: String, result: T, id: Int)

  object RpcClientOps {
    implicit class RpcClientExtensions(rpcClient: RpcClient) {
      def getNextEpoch: IO[Either[String, EpochToUpload]] =
        for {
          epochs <- rpcClient.call[List[EpochToUpload]]("sidechain_getSignaturesToUpload", List(1.asJson))
        } yield epochs.headOption.toRight("No next epoch was found")

      def getNextEpochs(nb: Int): IO[List[EpochToUpload]] =
        rpcClient.call[List[EpochToUpload]]("sidechain_getSignaturesToUpload", List(nb.asJson))

      def getEpochSignatures(epoch: Long): IO[GetSignaturesResponse] =
        rpcClient.call[GetSignaturesResponse]("sidechain_getEpochSignatures", List(epoch.asJson))

      def getCommittee(epoch: Long): IO[CommitteeResponse] =
        rpcClient.call[CommitteeResponse]("sidechain_getCommittee", List(epoch.asJson))

      def getStatus: IO[StatusPartial] =
        rpcClient.call[StatusPartial]("sidechain_getStatus", List.empty)
    }

  }
}
