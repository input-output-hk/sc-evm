package io.iohk.scevm.rpc.sidechain

import com.softwaremill.diffx.generic.auto.diffForCaseClass
import com.softwaremill.diffx.scalatest.DiffShouldMatcher
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{Address, Token}
import io.iohk.scevm.rpc.sidechain.SidechainController.{EpochAndRootHashes, GetPendingTransactionsResponse}
import io.iohk.scevm.sidechain.transactions.OutgoingTxId
import io.iohk.scevm.sidechain.{SidechainEpoch, ValidIncomingCrossChainTransaction}
import io.iohk.scevm.trustlesssidechain.cardano.{MainchainBlockNumber, MainchainTxHash}
import org.json4s.Extraction
import org.json4s.native.JsonMethods.parse
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SidechainJsonImplicitsSpec extends AnyWordSpec with Matchers with EitherValues with DiffShouldMatcher {

  "GetPendingTransactionsResponse" when {
    "pending and queued transactions exist" in {
      val tx1 = ValidIncomingCrossChainTransaction(
        Address("0x1111111111111111111111111111111111111111"),
        Token(BigInt("111", 16)),
        MainchainTxHash(Hex.decodeUnsafe("1111")),
        MainchainBlockNumber(38)
      )
      val tx2 = ValidIncomingCrossChainTransaction(
        Address("0x2222222222222222222222222222222222222222"),
        Token(BigInt("22", 16)),
        MainchainTxHash(Hex.decodeUnsafe("2222")),
        MainchainBlockNumber(38)
      )
      val tx3 = ValidIncomingCrossChainTransaction(
        Address("0x3333333333333333333333333333333333333333"),
        Token(BigInt("33", 16)),
        MainchainTxHash(Hex.decodeUnsafe("3333")),
        MainchainBlockNumber(38)
      )
      val input  = GetPendingTransactionsResponse(List(tx1, tx2), List(tx3))
      val output = SidechainJsonImplicits.get_pending_transactions.encodeJson(input)
      val expected = parse(
        """{
          |  "pending":[
          |    {
          |      "recipient": "0x1111111111111111111111111111111111111111",
          |      "value": "0x111",
          |      "txId": "0x1111",
          |      "stableAtMainchainBlock": 38
          |    },
          |    {
          |      "recipient": "0x2222222222222222222222222222222222222222",
          |      "value": "0x22",
          |      "txId": "0x2222",
          |      "stableAtMainchainBlock": 38
          |    }
          |  ],
          |  "queued": [
          |    {
          |      "recipient": "0x3333333333333333333333333333333333333333",
          |      "value": "0x33",
          |      "txId": "0x3333",
          |      "stableAtMainchainBlock": 38
          |    }
          |  ]
          |}""".stripMargin
      )

      output shouldMatchTo expected
    }

    "lists are empty" in {
      val input    = GetPendingTransactionsResponse(List.empty, List.empty)
      val output   = SidechainJsonImplicits.get_pending_transactions.encodeJson(input)
      val expected = parse("""{"pending": [], "queued": []}""")
      output shouldMatchTo expected
    }
  }

  "should encode correctly OutgoingTxId" in {
    import SidechainJsonImplicits._
    import org.json4s.native.JsonMethods._
    val document = compact(render(Extraction.decompose(OutgoingTxId(1))))
    assert(document == "1")
  }

  "should correctly encode epochAndRootHashes object" in {
    import SidechainJsonImplicits._
    import org.json4s.native.JsonMethods._

    val str = compact(render(Extraction.decompose(EpochAndRootHashes(SidechainEpoch(1), Nil))))
    assert(str == """{"epoch":1,"rootHashes":[]}""")
  }
}
