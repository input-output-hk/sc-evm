package io.iohk.scevm.rpc.sidechain

import io.iohk.scevm.rpc.serialization.{JsonEncoder, JsonMethodsImplicits, JsonSerializers}
import io.iohk.scevm.rpc.sidechain.SidechainController.{GetPendingTransactionsResponse, GetStatusResponse}
import org.json4s.{Extraction, Formats}

trait SidechainJsonImplicits extends JsonMethodsImplicits {

  implicit override val formats: Formats = SidechainJsonSerializers.All.apply(JsonSerializers.formats)

  implicit val get_mainchain_status: JsonEncoder[GetStatusResponse] =
    (t: GetStatusResponse) => Extraction.decompose(t)

  implicit val get_pending_transactions: JsonEncoder[GetPendingTransactionsResponse] =
    Extraction.decompose(_)
}

object SidechainJsonImplicits extends SidechainJsonImplicits
