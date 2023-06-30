package io.iohk.scevm.rpc.serialization

import cats.syntax.all._
import io.iohk.scevm.domain.{Address, BlockHash}
import io.iohk.scevm.rpc.controllers.EthTransactionController._
import io.iohk.scevm.rpc.controllers.PersonalController.{SendTransactionRequest, SendTransactionResponse}
import io.iohk.scevm.rpc.controllers.{BlockParam, EthBlockParam}
import io.iohk.scevm.rpc.domain.JsonRpcError
import io.iohk.scevm.rpc.domain.JsonRpcError.InvalidParams
import io.iohk.scevm.rpc.serialization.JsonSerializers._
import org.json4s.JsonAST.{JArray, JObject, JString, JValue}

object EthTransactionJsonMethodsImplicits extends JsonMethodsImplicits {
  implicit val eth_sendTransaction: JsonMethodCodec[SendTransactionRequest, SendTransactionResponse] =
    new JsonMethodCodec[SendTransactionRequest, SendTransactionResponse] {
      def decodeJson(params: Option[JArray]): Either[JsonRpcError, SendTransactionRequest] =
        params match {
          case Some(JArray(JObject(tx) :: _)) =>
            extractTx(tx.toMap).map(SendTransactionRequest)
          case _ =>
            Left(InvalidParams())
        }

      def encodeJson(t: SendTransactionResponse): JValue =
        encodeAsHex(t.txHash.byteString)
    }

  private def extractAddresses(values: List[(String, JValue)]): Seq[Address] =
    values
      .find(_._1 == "address")
      .flatMap { case (_, value) =>
        value match {
          case JArray(array) => array.traverse(AddressJsonSerializer.deserializePartial(formats).lift(_))
          case js: JString   => AddressJsonSerializer.deserializePartial(formats).lift(js).map(Seq(_))
          case _             => None
        }
      }
      .getOrElse(List.empty)

  private def extractTopics(values: List[(String, JValue)]): Seq[TopicSearch] =
    values
      .find(_._1 == "topics")
      .flatMap { case (_, value) =>
        value match {
          case JArray(array) => array.traverse(TopicSearchSerializer.deserializePartial(formats).lift(_))
          case _             => None
        }
      }
      .getOrElse(List.empty)

  private def extractBlockParam(attributeName: String, values: List[(String, JValue)]): EthBlockParam =
    values
      .find(_._1 == attributeName)
      .flatMap { case (_, value) => EthBlockParamSerializer.deserializePartial(formats).lift(value) }
      .getOrElse(BlockParam.Latest)

  private def extractBlockHashOption(values: List[(String, JValue)]): Option[BlockHash] =
    values.find(_._1 == "blockhash").flatMap { case (_, value) =>
      BlockHashSerializer.deserializePartial(formats).lift(value)
    }

  def extractGetLogsRequestByBlockHash(json: JObject): Option[GetLogsRequestByBlockHash] = {
    val values = json.obj

    val maybeBlockHash: Option[BlockHash] = extractBlockHashOption(values)
    val address                           = extractAddresses(values)
    val topics                            = extractTopics(values)

    for {
      blockHash <- maybeBlockHash
      if !values.map(_._1).contains("fromBlock") && !values.map(_._1).contains("toBlock")
    } yield GetLogsRequestByBlockHash(blockHash, address, topics)
  }

  def extractGetLogsRequestByRange(json: JObject): Option[GetLogsRequestByRange] = {
    val values = json.obj

    if (!values.map(_._1).contains("blockhash")) {
      val fromBlock = extractBlockParam("fromBlock", values)
      val toBlock   = extractBlockParam("toBlock", values)
      val address   = extractAddresses(values)
      val topics    = extractTopics(values)

      Some(GetLogsRequestByRange(fromBlock, toBlock, address, topics))
    } else {
      None
    }
  }
}
