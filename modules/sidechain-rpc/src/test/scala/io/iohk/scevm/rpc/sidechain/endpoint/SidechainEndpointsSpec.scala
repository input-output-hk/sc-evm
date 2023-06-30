package io.iohk.scevm.rpc.sidechain.endpoint

import io.circe.literal.JsonStringContext
import io.iohk.bytes.ByteString
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import io.iohk.scevm.rpc.sidechain.SidechainController._
import io.iohk.scevm.rpc.sidechain._
import io.iohk.scevm.rpc.sidechain.controller.SidechainFixtures
import io.iohk.scevm.sidechain.SidechainEpoch
import io.iohk.scevm.sidechain.transactions.OutgoingTxId
import org.json4s.{JArray, JInt, JString}
import org.scalatest.EitherValues

class SidechainEndpointsSpec extends ArmadilloScenarios with EitherValues {

  "sidechain_getCurrentCandidates" shouldPass new EndpointScenarios(SidechainApi.sidechain_getCurrentCandidates) {
    "return empty list if there are no candidates" in successScenario(
      expectedInputs = (),
      serviceResponse = Nil,
      expectedResponse = json"""[]"""
    )

    "return candidates when present" in successScenario(
      expectedInputs = (),
      serviceResponse = SidechainFixtures.GetCurrentCandidatesFixture.returnData,
      expectedResponse = SidechainFixtures.GetCurrentCandidatesFixture.expectedOutcome
    )
  }

  "sidechain_getCandidates" shouldPass new EndpointScenarios(SidechainApi.sidechain_getCandidates) {
    "handle valid request (using sidechain epoch)" in successScenario(
      params = JArray(List(JInt(0))),
      expectedInputs = SidechainEpochParam.ByEpoch(SidechainEpoch(0L)),
      serviceResponse = SidechainFixtures.GetCandidatesFixture.returnData,
      expectedResponse = SidechainFixtures.GetCandidatesFixture.expectedOutcome
    )

    "handle valid request (using latest)" in successScenario(
      params = JArray(List(JString("latest"))),
      expectedInputs = SidechainEpochParam.Latest,
      serviceResponse = SidechainFixtures.GetCandidatesFixture.returnData,
      expectedResponse = SidechainFixtures.GetCandidatesFixture.expectedOutcome
    )

    "handle invalid request (invalid sidechain epoch)" in errorScenario(
      params = JArray(List(JInt(-1))),
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )

    "handle invalid request (invalid string)" in errorScenario(
      params = JArray(List(JString("earliest"))),
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )
  }

  "sidechain_getCommittee" shouldPass new EndpointScenarios(SidechainApi.sidechain_getCommittee) {
    "handle valid request (using sidechain epoch)" in successScenario(
      params = JArray(List(JInt(1))),
      expectedInputs = SidechainEpochParam.ByEpoch(SidechainEpoch(1L)),
      serviceResponse = SidechainFixtures.GetCommitteeFixture.returnData,
      expectedResponse = SidechainFixtures.GetCommitteeFixture.expectedOutcome
    )

    "handle valid request (using latest)" in successScenario(
      params = JArray(List(JString("latest"))),
      expectedInputs = SidechainEpochParam.Latest,
      serviceResponse = SidechainFixtures.GetCommitteeFixture.returnData,
      expectedResponse = SidechainFixtures.GetCommitteeFixture.expectedOutcome
    )

    "handle invalid request (invalid sidechain epoch)" in errorScenario(
      params = JArray(List(JInt(-1))),
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )

    "handle invalid request (invalid string)" in errorScenario(
      params = JArray(List(JString("earliest"))),
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )
  }

  "sidechain_getEpochSignatures" shouldPass new EndpointScenarios(SidechainApi.sidechain_getEpochSignatures) {
    "handle valid request (using sidechain epoch)" in successScenario(
      params = JArray(List(JInt(1))),
      expectedInputs = SidechainEpochParam.ByEpoch(SidechainEpoch(1L)),
      serviceResponse = SidechainFixtures.GetEpochSignaturesFixtures.returnData,
      expectedResponse = SidechainFixtures.GetEpochSignaturesFixtures.expectedOutcome
    )

    "handle valid request (using latest)" in successScenario(
      params = JArray(List(JString("latest"))),
      expectedInputs = SidechainEpochParam.Latest,
      serviceResponse = SidechainFixtures.GetEpochSignaturesFixtures.returnData,
      expectedResponse = SidechainFixtures.GetEpochSignaturesFixtures.expectedOutcome
    )

    "handle valid request (return both handover and transaction signatures)" in successScenario(
      params = JArray(List(JInt(1))),
      expectedInputs = SidechainEpochParam.ByEpoch(SidechainEpoch(1L)),
      serviceResponse = SidechainFixtures.GetFullEpochSignaturesFixtures.returnData,
      expectedResponse = SidechainFixtures.GetFullEpochSignaturesFixtures.expectedOutcome
    )

    "handle invalid request (invalid sidechain epoch)" in errorScenario(
      params = JArray(List(JInt(-1))),
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )

    "handle invalid request (invalid string)" in errorScenario(
      params = JArray(List(JString("earliest"))),
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )
  }

  "sidechain_getStatus" shouldPass new EndpointScenarios(SidechainApi.sidechain_getStatus) {
    "handle valid request" in successScenario(
      params = JArray(List.empty),
      expectedInputs = (),
      serviceResponse = SidechainFixtures.GetStatusFixture.returnData,
      expectedResponse = SidechainFixtures.GetStatusFixture.expectedOutcome
    )
  }

  "sidechain_getOutgoingTransactions" shouldPass new EndpointScenarios(SidechainApi.sidechain_getOutgoingTransactions) {
    "handle valid request" in successScenario(
      params = JArray(List(JString("latest"))),
      expectedInputs = SidechainEpochParam.Latest,
      serviceResponse = SidechainFixtures.getOutgoingTxFixture.returnData,
      expectedResponse = SidechainFixtures.getOutgoingTxFixture.expectedOutcome
    )
  }

  "sidechain_getPendingTransactions" shouldPass new EndpointScenarios(SidechainApi.sidechain_getPendingTransactions) {
    "handle valid request" in successScenario(
      params = JArray(List.empty),
      expectedInputs = (),
      serviceResponse = SidechainFixtures.getPendingTxFixture.returnData,
      expectedResponse = SidechainFixtures.getPendingTxFixture.expectedOutcome
    )
  }

  "sidechain_getParams" shouldPass new EndpointScenarios(SidechainApi.sidechain_getParams) {
    "handle valid request" in successScenario(
      params = JArray(List.empty),
      expectedInputs = (),
      serviceResponse = SidechainFixtures.getParamsFixture.returnData,
      expectedResponse = SidechainFixtures.getParamsFixture.expectedOutcome
    )
  }

  "sidechain_getOutgoingTransactionMerkleProof" shouldPass new EndpointScenarios(
    SidechainApi.sidechain_getOutgoingTxMerkleProof
  ) {
    "handle valid request (using latest)" in successScenario(
      params = JArray(List(JString("latest"), JInt(11))),
      expectedInputs = (SidechainEpochParam.Latest, OutgoingTxId(11)),
      serviceResponse = SidechainFixtures.getOutgoingTxMerkleProofFixture.returnData,
      expectedResponse = SidechainFixtures.getOutgoingTxMerkleProofFixture.expectedOutcome
    )

    "handle valid request (using strict epoch)" in successScenario(
      params = JArray(List(JInt(11), JInt(11))),
      expectedInputs = (SidechainEpochParam.ByEpoch(SidechainEpoch(11)), OutgoingTxId(11)),
      serviceResponse = SidechainFixtures.getOutgoingTxMerkleProofFixture.returnData,
      expectedResponse = SidechainFixtures.getOutgoingTxMerkleProofFixture.expectedOutcome
    )

    "handle valid request when there is no proof" in successScenario(
      params = JArray(List(JString("latest"), JInt(11))),
      expectedInputs = (SidechainEpochParam.Latest, OutgoingTxId(11)),
      serviceResponse = SidechainFixtures.getOutgoingTxMerkleProofNoProofFixture.returnData,
      expectedResponse = SidechainFixtures.getOutgoingTxMerkleProofNoProofFixture.expectedOutcome
    )

    "handle invalid request (invalid sidechain epoch)" in errorScenario(
      params = JArray(List(JInt(-1))),
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )

    "handle invalid request (invalid string)" in errorScenario(
      params = JArray(List(JString("earliest"))),
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )
  }

  "sidechain_getSignaturesToUpload" shouldPass new EndpointScenarios(
    SidechainApi.sidechain_getSignaturesToUpload
  ) {
    "correctly encode response if there are outgoing transactions" in successScenario(
      expectedInputs = None,
      serviceResponse = List(EpochAndRootHashes(SidechainEpoch(1), List(ByteString.fromInts(1, 2, 3, 4)))),
      expectedResponse = json"""[ { "epoch": 1, "rootHashes": ["0x01020304"] } ]"""
    )

    "correctly encode response if there are no outgoing transactions but handover signature needs to be uploaded" in successScenario(
      expectedInputs = None,
      serviceResponse = List(EpochAndRootHashes(SidechainEpoch(1), Nil)),
      expectedResponse = json"""[ { "epoch": 1, "rootHashes": [] } ]"""
    )

    "correctly encode response if there are no outgoing transactions and no handover signatures to upload" in successScenario(
      expectedInputs = None,
      serviceResponse = Nil,
      expectedResponse = json"[]"
    )

    "correctly encode limit parameter" in successScenario(
      params = JArray(List(JInt(126))),
      expectedInputs = Some(126),
      serviceResponse = Nil,
      expectedResponse = json"[]"
    )
  }
}
