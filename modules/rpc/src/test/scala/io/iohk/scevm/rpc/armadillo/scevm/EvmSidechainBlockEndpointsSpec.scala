package io.iohk.scevm.rpc.armadillo.scevm

import io.circe.Json
import io.circe.literal.JsonStringContext
import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{BlockHash, BlockNumber}
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import io.iohk.scevm.rpc.controllers.BlockParam
import io.iohk.scevm.rpc.domain.RpcBlockResponse
import io.iohk.scevm.rpc.domain.RpcBlockResponse.EmptyHash
import io.iohk.scevm.utils.SystemTime.TimestampSeconds
import org.json4s.{JArray, JBool, JString}

class EvmSidechainBlockEndpointsSpec extends ArmadilloScenarios {

  "evmsidechain_getBlockByNumber" shouldPass new EndpointScenarios(EvmSidechainBlockApi.evmsidechain_getBlockByNumber) {
    "handle valid request (by-number)" in successScenario(
      params = JArray(JString("0x01") :: JBool(false) :: Nil),
      expectedInputs = (BlockParam.ByNumber(BlockNumber(BigInt(1))), false),
      serviceResponse = Option(Fixtures.eth_getBlockByNumber.RpcBlockResponse),
      expectedResponse = Fixtures.eth_getBlockByNumber.ExpectedResponse
    )

    "handle valid request (earliest)" in successScenario(
      params = JArray(JString("latest") :: JBool(false) :: Nil),
      expectedInputs = (BlockParam.Latest, false),
      serviceResponse = Option(Fixtures.eth_getBlockByNumber.RpcBlockResponse),
      expectedResponse = Fixtures.eth_getBlockByNumber.ExpectedResponse
    )

    "handle valid request (pending)" in successScenario(
      params = JArray(JString("pending") :: JBool(false) :: Nil),
      expectedInputs = (BlockParam.Pending, false),
      serviceResponse = Option(Fixtures.eth_getBlockByNumber.RpcBlockResponse),
      expectedResponse = Fixtures.eth_getBlockByNumber.ExpectedResponse
    )

    "handle valid request (latest)" in successScenario(
      params = JArray(JString("latest") :: JBool(false) :: Nil),
      expectedInputs = (BlockParam.Latest, false),
      serviceResponse = Option(Fixtures.eth_getBlockByNumber.RpcBlockResponse),
      expectedResponse = Fixtures.eth_getBlockByNumber.ExpectedResponse
    )

    "handle invalid request" in successScenario(
      params = JArray(JString("stable") :: JBool(false) :: Nil),
      expectedInputs = (BlockParam.Stable, false),
      serviceResponse = Option(Fixtures.eth_getBlockByNumber.RpcBlockResponse),
      expectedResponse = Fixtures.eth_getBlockByNumber.ExpectedResponse
    )
  }

  object Fixtures {

    object eth_getBlockByNumber {
      val RpcBlockResponse = new RpcBlockResponse(
        baseFeePerGas = None,
        difficulty = 1,
        gasLimit = 1,
        gasUsed = 1,
        logsBloom = ByteString("b"),
        miner = ByteString("abcd"),
        mixHash = None,
        nonce = None,
        number = BlockNumber(1),
        parentHash = BlockHash(ByteString("abcd")),
        receiptsRoot = ByteString("fff"),
        sha3Uncles = EmptyHash,
        size = BigInt(1),
        stateRoot = ByteString("aaaa"),
        timestamp = TimestampSeconds(1000_000_000),
        totalDifficulty = None,
        transactions = Right(Nil),
        transactionsRoot = ByteString("cccc"),
        uncles = Nil,
        hash = None
      )

      val ExpectedResponse: Json =
        json"""{
                "difficulty"       : "0x1",
                "extraData"        : "0x0000000000000000000000000000000000000000",
                "gasLimit"         : "0x1",
                "gasUsed"          : "0x1",
                "logsBloom"        : "0x62",
                "miner"            : "0x61626364",
                "number"           : "0x1",
                "parentHash"       : "0x61626364",
                "receiptsRoot"     : "0x666666",
                "sha3Uncles"       : "0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
                "size"             : "0x1",
                "stateRoot"        : "0x61616161",
                "timestamp"        : "0x3b9aca00",
                "transactions"     : [],
                "transactionsRoot" : "0x63636363",
                "uncles"           : []
      }"""
    }
  }

}
