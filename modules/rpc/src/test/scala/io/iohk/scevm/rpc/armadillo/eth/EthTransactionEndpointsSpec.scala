package io.iohk.scevm.rpc.armadillo.eth

import io.circe.literal.JsonStringContext
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{Address, BlockHash, BlockNumber, Gas, Token, TransactionHash}
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import io.iohk.scevm.rpc.controllers.EthTransactionController.{GetLogsRequestByBlockHash, GetLogsRequestByRange}
import io.iohk.scevm.rpc.controllers.{
  BlockParam,
  BlocksFixture,
  TransactionLog,
  TransactionLogWithRemoved,
  TransactionReceiptResponse
}
import io.iohk.scevm.rpc.domain.{RpcFullTransactionResponse, TransactionRequest}
import io.iohk.scevm.testing.TestCoreConfigs
import org.json4s.JsonAST.{JArray, JObject, JString}
import org.json4s.JsonDSL._

class EthTransactionEndpointsSpec extends ArmadilloScenarios with BlocksFixture {

  private val ethGetLogsInput = Seq(
    TransactionLogWithRemoved(
      removed = false,
      logIndex = BigInt(1),
      transactionIndex = BigInt(2),
      transactionHash = TransactionHash(
        Hex.decodeUnsafe("446a78c408ce6046de825899c749a52aa28216d17ffc9e95164de1210809b671")
      ),
      blockHash = BlockHash(
        Hex.decodeUnsafe("57519373bbf1c9a68eff2d7c2c47fffbd693edfcadda5fed8187b41366699337")
      ),
      blockNumber = BlockNumber(BigInt(3)),
      address = Address(42),
      data = Hex.decodeUnsafe("11111111"),
      topics = Seq(
        Hex.decodeUnsafe("22222222"),
        Hex.decodeUnsafe("33333333")
      )
    )
  )

  private val ethGetLogsExpectedOutput =
    json"""
        [
          {
            "removed": false,
            "logIndex": "0x1",
            "transactionIndex": "0x2",
            "transactionHash": "0x446a78c408ce6046de825899c749a52aa28216d17ffc9e95164de1210809b671",
            "blockHash": "0x57519373bbf1c9a68eff2d7c2c47fffbd693edfcadda5fed8187b41366699337",
            "blockNumber": "0x3",
            "address": "0x000000000000000000000000000000000000002a",
            "data": "0x11111111",
            "topics": [
              "0x22222222",
              "0x33333333"
            ]
          }
        ]
        """

  "eth_gasPrice" shouldPass new EndpointScenarios(EthTransactionApi.eth_gasPrice) {
    "handle valid request" in successScenario(
      params = JArray(List.empty),
      expectedInputs = (),
      serviceResponse = Gas(BigInt(42)),
      expectedResponse = json""""0x2a""""
    )
  }

  "eth_getLogs" shouldPass new EndpointScenarios(EthTransactionApi.eth_getLogs) {
    "handle valid request (range version)" in successScenario(
      params = JArray(List(JObject("fromBlock" -> JString("earliest"), "toBlock" -> JString("latest")))),
      expectedInputs = GetLogsRequestByRange(BlockParam.Earliest, BlockParam.Latest, Seq.empty, Seq.empty),
      serviceResponse = ethGetLogsInput,
      expectedResponse = ethGetLogsExpectedOutput
    )

    "handle valid request (blockHash version)" in successScenario(
      params = JArray(
        List(JObject("blockhash" -> JString("0x57519373bbf1c9a68eff2d7c2c47fffbd693edfcadda5fed8187b41366699337")))
      ),
      expectedInputs = GetLogsRequestByBlockHash(
        BlockHash(Hex.decodeUnsafe("57519373bbf1c9a68eff2d7c2c47fffbd693edfcadda5fed8187b41366699337")),
        Seq.empty,
        Seq.empty
      ),
      serviceResponse = ethGetLogsInput,
      expectedResponse = ethGetLogsExpectedOutput
    )

    "handle invalid request (both blockHash and fromBlock/toBlock are defined)" in errorScenario(
      params = JArray(
        List(
          JObject(
            "fromBlock" -> JString("earliest"),
            "toBlock"   -> JString("latest"),
            "blockhash" -> JString("0x57519373bbf1c9a68eff2d7c2c47fffbd693edfcadda5fed8187b41366699337")
          )
        )
      ),
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )

    "handle valid request (blockParam = stable)" in errorScenario(
      params = JArray(List(JObject("fromBlock" -> JString("stable"), "toBlock" -> JString("stable")))),
      serverError = InvalidParamsError,
      expectedResponse = InvalidParamsJson
    )
  }

  "eth_getTransactionByHash" shouldPass new EndpointScenarios(EthTransactionApi.eth_getTransactionByHash) {
    "handle valid request (legacy transaction)" in successScenario(
      params = JArray(List(JString(Block3125369.legacyTransaction.hash.toHex))),
      expectedInputs = Block3125369.legacyTransaction.hash,
      serviceResponse = Some(
        RpcFullTransactionResponse
          .from(
            Block3125369.legacyTransaction,
            0,
            Block3125369.block.hash,
            Block3125369.block.number,
            TestCoreConfigs.blockchainConfig.chainId
          )
          .value
      ),
      expectedResponse = expectedLegacyTransactionSerialized
    )

    "handle valid request (EIP-2930 transaction (TypedTransaction 01))" in successScenario(
      params = JArray(List(JString(Block3125369.transactionType01.hash.toHex))),
      expectedInputs = Block3125369.transactionType01.hash,
      serviceResponse = Some(
        RpcFullTransactionResponse
          .from(
            Block3125369.transactionType01,
            1,
            Block3125369.block.hash,
            Block3125369.block.number,
            TestCoreConfigs.blockchainConfig.chainId
          )
          .value
      ),
      expectedResponse = expectedTransactionType01Serialized
    )

    "handle valid request (EIP-1559 transaction (TypedTransaction 02))" in successScenario(
      params = JArray(List(JString(Block3125369.transactionType02.hash.toHex))),
      expectedInputs = Block3125369.transactionType02.hash,
      serviceResponse = Some(
        RpcFullTransactionResponse
          .from(
            Block3125369.transactionType02,
            2,
            Block3125369.block.hash,
            Block3125369.block.number,
            TestCoreConfigs.blockchainConfig.chainId
          )
          .value
      ),
      expectedResponse = expectedTransactionType02Serialized
    )

    "handle valid request (transaction without receivingAddress)" in successScenario(
      params = JArray(List(JString(Block123.legacyTransactionWithoutReceivingAddress.hash.toHex))),
      expectedInputs = Block123.legacyTransactionWithoutReceivingAddress.hash,
      serviceResponse = Some(
        RpcFullTransactionResponse
          .from(
            Block123.legacyTransactionWithoutReceivingAddress,
            0,
            Block123.block.hash,
            Block123.block.number,
            TestCoreConfigs.blockchainConfig.chainId
          )
          .value
      ),
      expectedResponse = expectedLegacyTransactionWithoutReceivingAddressSerialized
    )
  }

  "eth_getTransactionReceipt" shouldPass new EndpointScenarios(EthTransactionApi.eth_getTransactionReceipt) {
    "handle valid request (receipt post byzantium HF)" in successScenario(
      params = JArray(List(JString(Block3125369.legacyTransaction.hash.toHex))),
      expectedInputs = Block3125369.legacyTransaction.hash,
      serviceResponse = Some(
        TransactionReceiptResponse(
          transactionHash = TransactionHash(Hex.decodeUnsafe("23" * 32)),
          transactionIndex = 1,
          blockNumber = Block3125369.header.number,
          blockHash = Block3125369.header.hash,
          from = Address(1),
          to = None,
          cumulativeGasUsed = 42 * 10,
          gasUsed = 42,
          contractAddress = Some(Address(42)),
          logs = Seq(
            TransactionLog(
              logIndex = 0,
              transactionIndex = 1,
              transactionHash = TransactionHash(Hex.decodeUnsafe("23" * 32)),
              blockHash = Block3125369.header.hash,
              blockNumber = Block3125369.header.number,
              address = Address(42),
              data = Hex.decodeUnsafe("43" * 32),
              topics = Seq(Hex.decodeUnsafe("44" * 32), Hex.decodeUnsafe("45" * 32))
            )
          ),
          logsBloom = Hex.decodeUnsafe("23" * 32),
          root = None,
          status = Some(1)
        )
      ),
      expectedResponse = json"""
          {
            "transactionHash": "0x2323232323232323232323232323232323232323232323232323232323232323",
            "transactionIndex": "0x1",
            "blockNumber": "0x2fb079",
            "blockHash": "0xe7cc25a984407887b9ea630bc4ea7727020ce5d6c262da485fb9bc42c0df2813",
            "from": "0x0000000000000000000000000000000000000001",
            "cumulativeGasUsed": "0x1a4",
            "gasUsed": "0x2a",
            "contractAddress": "0x000000000000000000000000000000000000002a",
            "logs": [
              {
                "logIndex": "0x0",
                "transactionIndex": "0x1",
                "transactionHash": "0x2323232323232323232323232323232323232323232323232323232323232323",
                "blockHash": "0xe7cc25a984407887b9ea630bc4ea7727020ce5d6c262da485fb9bc42c0df2813",
                "blockNumber": "0x2fb079",
                "address": "0x000000000000000000000000000000000000002a",
                "data": "0x4343434343434343434343434343434343434343434343434343434343434343",
                "topics": [
                  "0x4444444444444444444444444444444444444444444444444444444444444444",
                  "0x4545454545454545454545454545454545454545454545454545454545454545"
                ]
              }
            ],
            "logsBloom": "0x2323232323232323232323232323232323232323232323232323232323232323",
            "status": "0x1"
          }
          """
    )
    "handle valid request (receipt pre byzantium HF)" in successScenario(
      params = JArray(List(JString(Block3125369.legacyTransaction.hash.toHex))),
      expectedInputs = Block3125369.legacyTransaction.hash,
      serviceResponse = Some(
        TransactionReceiptResponse(
          transactionHash = TransactionHash(Hex.decodeUnsafe("23" * 32)),
          transactionIndex = 1,
          blockNumber = Block3125369.header.number,
          blockHash = Block3125369.header.hash,
          from = Address(1),
          to = None,
          cumulativeGasUsed = 42 * 10,
          gasUsed = 42,
          contractAddress = Some(Address(42)),
          logs = Seq(
            TransactionLog(
              logIndex = 0,
              transactionIndex = 1,
              transactionHash = TransactionHash(Hex.decodeUnsafe("23" * 32)),
              blockHash = Block3125369.header.hash,
              blockNumber = Block3125369.header.number,
              address = Address(42),
              data = Hex.decodeUnsafe("43" * 32),
              topics = Seq(Hex.decodeUnsafe("44" * 32), Hex.decodeUnsafe("45" * 32))
            )
          ),
          logsBloom = Hex.decodeUnsafe("23" * 32),
          root = Some(Hex.decodeUnsafe("23" * 32)),
          status = None
        )
      ),
      expectedResponse = json"""
              {
                "transactionHash": "0x2323232323232323232323232323232323232323232323232323232323232323",
                "transactionIndex": "0x1",
                "blockNumber": "0x2fb079",
                "blockHash": "0xe7cc25a984407887b9ea630bc4ea7727020ce5d6c262da485fb9bc42c0df2813",
                "from": "0x0000000000000000000000000000000000000001",
                "cumulativeGasUsed": "0x1a4",
                "gasUsed": "0x2a",
                "contractAddress": "0x000000000000000000000000000000000000002a",
                "logs": [
                  {
                    "logIndex": "0x0",
                    "transactionIndex": "0x1",
                    "transactionHash": "0x2323232323232323232323232323232323232323232323232323232323232323",
                    "blockHash": "0xe7cc25a984407887b9ea630bc4ea7727020ce5d6c262da485fb9bc42c0df2813",
                    "blockNumber": "0x2fb079",
                    "address": "0x000000000000000000000000000000000000002a",
                    "data": "0x4343434343434343434343434343434343434343434343434343434343434343",
                    "topics": [
                      "0x4444444444444444444444444444444444444444444444444444444444444444",
                      "0x4545454545454545454545454545454545454545454545454545454545454545"
                    ]
                  }
                ],
                "logsBloom": "0x2323232323232323232323232323232323232323232323232323232323232323",
                "root": "0x2323232323232323232323232323232323232323232323232323232323232323"
              }
              """
    )
  }

  "eth_sendRawTransaction" shouldPass new EndpointScenarios(EthTransactionApi.eth_sendRawTransaction) {
    "handle valid request" in successScenario(
      params = JArray(List(JString("0x0123456789ABCDEF"))),
      expectedInputs = Hex.decodeUnsafe("0123456789ABCDEF"),
      serviceResponse = TransactionHash(Hex.decodeUnsafe("23" * 32)),
      expectedResponse = json""""0x2323232323232323232323232323232323232323232323232323232323232323""""
    )
  }

  "eth_sendTransaction" shouldPass new EndpointScenarios(EthTransactionApi.eth_sendTransaction) {
    val transactionRequestWithHexadecimalValue: JObject = JObject(
      "from"  -> Address(42).toString,
      "to"    -> Address(123).toString,
      "value" -> "3E8"
    )
    val transactionRequestWithIntegerValue: JObject = JObject(
      "from"  -> Address(42).toString,
      "to"    -> Address(123).toString,
      "value" -> 1000
    )
    val txHash: ByteString = ByteString(1, 2, 3, 4)
    val hashHex: String    = s"0x${Hex.toHexString(txHash.toArray)}"

    "handle valid request (value as hexadecimal value)" in successScenario(
      params = JArray(transactionRequestWithHexadecimalValue :: Nil),
      expectedInputs = TransactionRequest(Address(42), Some(Address(123)), Some(Token(1000))),
      serviceResponse = TransactionHash(txHash),
      expectedResponse = json"""$hashHex"""
    )

    "handle valid request (value as integer value)" in successScenario(
      params = JArray(transactionRequestWithIntegerValue :: Nil),
      expectedInputs = TransactionRequest(Address(42), Some(Address(123)), Some(Token(1000))),
      serviceResponse = TransactionHash(txHash),
      expectedResponse = json"""$hashHex"""
    )
  }
}
