package io.iohk.scevm.rpc.serialization

import cats.implicits.toTraverseOps
import io.iohk.scevm.rpc.controllers.SanityController.CheckChainConsistencyRequest
import io.iohk.scevm.rpc.domain.JsonRpcError
import org.json4s.JsonAST.{JObject, JValue}

object SanityJsonMethodsImplicits extends JsonMethodsImplicits {

  def extractCall(obj: JObject): Either[JsonRpcError, CheckChainConsistencyRequest] = for {
    fromOpt <- (obj \ "from").extractOpt[JValue].flatTraverse(optionalBlockNumber)
    toOpt   <- (obj \ "to").extractOpt[JValue].flatTraverse(optionalBlockNumber)
  } yield CheckChainConsistencyRequest(fromOpt, toOpt)

}
