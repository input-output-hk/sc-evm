package io.iohk.scevm.rpc.armadillo.eth

import io.circe.Json
import io.circe.literal.JsonStringContext
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{BlockHash, BlockNumber}
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import io.iohk.scevm.rpc.controllers.{BlockParam, BlocksFixture}
import io.iohk.scevm.rpc.domain.RpcBlockResponse
import io.iohk.scevm.rpc.domain.RpcBlockResponse.EmptyHash
import io.iohk.scevm.utils.SystemTime.TimestampSeconds
import org.json4s.JsonAST.{JArray, JBool, JString}

class EthBlocksEndpointsSpec extends ArmadilloScenarios with BlocksFixture {

  "eth_blockNumber" shouldPass new EndpointScenarios(EthBlocksApi.eth_blockNumber) {
    "handle valid request" in successScenario(
      params = JArray(List.empty),
      expectedInputs = (),
      serviceResponse = BlockNumber(42),
      expectedResponse = json""""0x2a""""
    )
  }

  "eth_getBlockByHash" shouldPass new EndpointScenarios(EthBlocksApi.eth_getBlockByHash) {
    "handle valid request" in successScenario(
      params = JArray(JString("0x42") :: JBool(false) :: Nil),
      expectedInputs = (BlockHash(Hex.decodeUnsafe("42")), false),
      serviceResponse = Option(Fixtures.eth_getBlockByHash.RpcBlockResponse),
      expectedResponse = Fixtures.eth_getBlockByHash.ExpectedResponse
    )
  }

  "eth_getBlockByNumber" shouldPass new EndpointScenarios(EthBlocksApi.eth_getBlockByNumber) {
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

    "handle invalid request" in errorScenario(
      params = JArray(JString("stable") :: JBool(false) :: Nil),
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )
  }

  "eth_getBlockTransactionCountByHash" shouldPass new EndpointScenarios(
    EthBlocksApi.eth_getBlockTransactionCountByHash
  ) {
    "handle valid request" in successScenario(
      params = JArray(List(JString(Block3125369.header.hash.toHex))),
      expectedInputs = Block3125369.header.hash,
      serviceResponse = Some(Block3125369.body.transactionList.length),
      expectedResponse = json"3"
    )
  }

  "eth_getBlockTransactionCountByNumber" shouldPass new EndpointScenarios(
    EthBlocksApi.eth_getBlockTransactionCountByNumber
  ) {
    "handle valid request (by-number)" in successScenario(
      params = JArray(List(JString("0x01"))),
      expectedInputs = BlockParam.ByNumber(BlockNumber(BigInt(1))),
      serviceResponse = BigInt(Block3125369.body.transactionList.length),
      expectedResponse = json""""0x3""""
    )

    "handle valid request (earliest)" in successScenario(
      params = JArray(List(JString("latest"))),
      expectedInputs = BlockParam.Latest,
      serviceResponse = BigInt(Block3125369.body.transactionList.length),
      expectedResponse = json""""0x3""""
    )

    "handle valid request (pending)" in successScenario(
      params = JArray(List(JString("pending"))),
      expectedInputs = BlockParam.Pending,
      serviceResponse = BigInt(Block3125369.body.transactionList.length),
      expectedResponse = json""""0x3""""
    )

    "handle valid request (latest)" in successScenario(
      params = JArray(List(JString("latest"))),
      expectedInputs = BlockParam.Latest,
      serviceResponse = BigInt(Block3125369.body.transactionList.length),
      expectedResponse = json""""0x3""""
    )

    "handle invalid request" in errorScenario(
      params = JArray(List(JString("stable"))),
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )
  }

  object Fixtures {

    object eth_getBlockByHash {
      val RpcBlockResponse: RpcBlockResponse =
        eth_getBlockByNumber.RpcBlockResponse.copy(hash = Some(BlockHash(Hex.decodeUnsafe("42"))))

      val ExpectedResponse: Json =
        eth_getBlockByNumber.ExpectedResponse.mapObject(obj => obj.add("hash", Json.fromString("0x42")))
    }

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
