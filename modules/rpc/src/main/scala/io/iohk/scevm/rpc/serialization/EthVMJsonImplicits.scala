package io.iohk.scevm.rpc.serialization

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Gas, Token}
import io.iohk.scevm.rpc.controllers.EthVMController.CallTransaction
import io.iohk.scevm.rpc.domain.JsonRpcError
import org.json4s.JsonAST.JObject

object EthVMJsonImplicits extends JsonMethodsImplicits {

  def extractCall(obj: JObject): Either[JsonRpcError, CallTransaction] = {
    def toEitherOpt[A, B](opt: Option[Either[A, B]]): Either[A, Option[B]] =
      opt match {
        case Some(Right(v)) => Right(Option(v))
        case Some(Left(e))  => Left(e)
        case None           => Right(None)
      }

    for {
      from     <- toEitherOpt((obj \ "from").extractOpt[String].map(extractBytes))
      to       <- toEitherOpt((obj \ "to").extractOpt[String].map(extractAddress))
      gas      <- optionalQuantity(obj \ "gas")
      gasPrice <- optionalQuantity(obj \ "gasPrice")
      value    <- optionalQuantity(obj \ "value")
      data <- toEitherOpt(
                (obj \ "data")
                  .extractOpt[String]
                  .orElse((obj \ "input").extractOpt[String])
                  .map(extractBytes)
              )
    } yield CallTransaction(
      from = from,
      to = to,
      gas = gas.map(Gas.apply),
      gasPrice = gasPrice.map(Token.apply),
      value = Token(value.getOrElse(0)),
      input = data.getOrElse(ByteString.empty)
    )
  }
}
