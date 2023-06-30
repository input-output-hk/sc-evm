package io.iohk.scevm.rpc.sidechain

import io.iohk.armadillo.json.json4s._
import io.iohk.armadillo.{JsonRpcEndpoint, JsonRpcError, JsonRpcInput, errorNoData}
import io.iohk.scevm.rpc.armadillo.Api
import io.iohk.scevm.rpc.sidechain.CrossChainTransactionController.GetOutgoingTransactionsResponse
import io.iohk.scevm.rpc.sidechain.SidechainController._
import io.iohk.scevm.sidechain.transactions.OutgoingTxId

object SidechainApi extends Api with SidechainJsonImplicits with SidechainSchemas with SidechainDocumentationImplicits {

  private val SidechainEpochParamInput: JsonRpcInput[SidechainEpochParam] = input[SidechainEpochParam]("sidechainEpoch")

  // scalastyle:off line.size.limit
  val sidechain_getCurrentCandidates: JsonRpcEndpoint[Unit, JsonRpcError[Unit], List[CandidateRegistrationEntry]] =
    baseEndpoint("sidechain_getCurrentCandidates", "Returns the most recent state of registrations.")
      .out(output[List[CandidateRegistrationEntry]]("candidates"))
      .errorOut(errorNoData)

  val sidechain_getCandidates
      : JsonRpcEndpoint[SidechainEpochParam, JsonRpcError[Unit], List[CandidateRegistrationEntry]] =
    baseEndpoint(
      "sidechain_getCandidates",
      "Returns the state of candidates registrations at the beginning of the given sidechain epoch."
    )
      .in(SidechainEpochParamInput)
      .out(output[List[CandidateRegistrationEntry]]("candidates"))
      .errorOut(errorNoData)

  val sidechain_getCommittee: JsonRpcEndpoint[SidechainEpochParam, JsonRpcError[Unit], GetCommitteeResponse] =
    baseEndpoint("sidechain_getCommittee", "Returns the committee for the given sidechain epoch.")
      .in(SidechainEpochParamInput)
      .out(output[GetCommitteeResponse]("committee"))
      .errorOut(errorNoData)

  val sidechain_getEpochSignatures: JsonRpcEndpoint[SidechainEpochParam, JsonRpcError[Unit], GetSignaturesResponse] =
    baseEndpoint(
      "sidechain_getEpochSignatures",
      "Returns the data required for committee handover and Merkle root insertion: sidechain parameters, next committee, Merkle root of outgoing transactions, and corresponding signatures."
    )
      .in(SidechainEpochParamInput)
      .out(output[GetSignaturesResponse]("signatures"))
      .errorOut(errorNoData)

  val sidechain_getStatus: JsonRpcEndpoint[Unit, JsonRpcError[Unit], GetStatusResponse] =
    baseEndpoint(
      "sidechain_getStatus",
      "Returns data related to the status of both the main chain and the sidechain, like their best and stable blocks, as well as the current epoch or the timestamp associated to the next epoch."
    )
      .out(output[GetStatusResponse]("status"))
      .errorOut(errorNoData)

  val sidechain_getOutgoingTransactions
      : JsonRpcEndpoint[SidechainEpochParam, JsonRpcError[Unit], GetOutgoingTransactionsResponse] =
    baseEndpoint("sidechain_getOutgoingTransactions", "Returns the list of transactions from sidechain to main chain.")
      .in(SidechainEpochParamInput)
      .out(output[GetOutgoingTransactionsResponse]("outgoingTransactions"))
      .errorOut(errorNoData)

  val sidechain_getPendingTransactions: JsonRpcEndpoint[Unit, JsonRpcError[Unit], GetPendingTransactionsResponse] =
    baseEndpoint(
      "sidechain_getPendingTransactions",
      "Returns the list of pending and queued transactions from main chain to sidechain. Queued transactions correspond to transactions that are already stable on the main chain, and pending transactions correspond to transactions that are not stable yet."
    )
      .out(output[GetPendingTransactionsResponse]("transactions"))
      .errorOut(errorNoData)

  val sidechain_getParams: JsonRpcEndpoint[Unit, Unit, GetSidechainParamsResponse] =
    baseEndpoint(
      "sidechain_getParams",
      "Returns some useful parameters of the chain, like `genesisMintUtxo`, `genesisCommitteeUtxo`, `genesisHash` or `chainId`."
    )
      .out(output[GetSidechainParamsResponse]("sidechain-params"))

  val sidechain_getOutgoingTxMerkleProof
      : JsonRpcEndpoint[(SidechainEpochParam, OutgoingTxId), JsonRpcError[Unit], GetMerkleProofResponse] =
    baseEndpoint(
      "sidechain_getOutgoingTxMerkleProof",
      "Returns the Merkle proof of specified outgoing transaction."
    )
      .in(SidechainEpochParamInput.and(input[OutgoingTxId]("transactionId")))
      .out(output[GetMerkleProofResponse]("outgoingTransactionMerkleRootResponse"))
      .errorOut(errorNoData)

  val sidechain_getSignaturesToUpload: JsonRpcEndpoint[Option[Int], JsonRpcError[Unit], GetSignaturesToUploadResponse] =
    baseEndpoint(
      "sidechain_getSignaturesToUpload",
      "Returns the list of signatures to upload. If `rootHashes` is empty, it means that all transaction batches were uploaded to main chain, but handover signatures were not."
    )
      .in(
        input[Option[Int]]("limit")
          .description("Limit number of items returned. Default 100")
      )
      .out(output[GetSignaturesToUploadResponse]("getSignaturesToUploadResponse"))
      .errorOut(errorNoData)
  // scalastyle:on line.size.limit
}
