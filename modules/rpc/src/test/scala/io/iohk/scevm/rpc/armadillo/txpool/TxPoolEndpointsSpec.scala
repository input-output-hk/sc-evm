package io.iohk.scevm.rpc.armadillo.txpool

import io.circe.Json
import io.circe.literal.JsonStringContext
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSASignature
import io.iohk.scevm.domain.{Address, BlockHash, BlockNumber, LegacyTransaction, Nonce, TransactionHash}
import io.iohk.scevm.rpc.armadillo.ArmadilloScenarios
import io.iohk.scevm.rpc.controllers.TxPoolController.GetContentResponse
import io.iohk.scevm.rpc.controllers.{BlocksFixture, TxPoolController}
import io.iohk.scevm.rpc.domain.{RpcFullTransactionResponse, RpcLegacyTransactionResponse}
import io.iohk.scevm.testing.TestCoreConfigs
import org.json4s.JArray
import org.scalatest.EitherValues

class TxPoolEndpointsSpec extends ArmadilloScenarios with EitherValues {

  "txpool_content" shouldPass new EndpointScenarios(TxPoolApi.txpool_content) {
    "handle valid request (pending and queued transactions)" in successScenario(
      params = Fixtures.txpool_content.RequestBody,
      expectedInputs = (),
      serviceResponse = Fixtures.txpool_content.Base.CallResponse,
      expectedResponse = Fixtures.txpool_content.Base.ExpectedResponse
    )

    "handle valid request (no transactions)" in successScenario(
      params = Fixtures.txpool_content.RequestBody,
      expectedInputs = (),
      serviceResponse = Fixtures.txpool_content.NoTransactions.CallResponse,
      expectedResponse = Fixtures.txpool_content.NoTransactions.ExpectedResponse
    )

    "handle valid request (no queued transactions)" in successScenario(
      params = Fixtures.txpool_content.RequestBody,
      expectedInputs = (),
      serviceResponse = Fixtures.txpool_content.NoQueuedTransactions.CallResponse,
      expectedResponse = Fixtures.txpool_content.NoQueuedTransactions.ExpectedResponse
    )

    "handle valid request (no pending transactions)" in successScenario(
      params = Fixtures.txpool_content.RequestBody,
      expectedInputs = (),
      serviceResponse = Fixtures.txpool_content.NoPendingTransactions.CallResponse,
      expectedResponse = Fixtures.txpool_content.NoPendingTransactions.ExpectedResponse
    )
  }

  object Fixtures extends BlocksFixture {
    val templateSender: Address = Address(0x123456)
    val legacyTransaction: RpcFullTransactionResponse = RpcFullTransactionResponse
      .from(
        Block3125369.legacyTransaction,
        0,
        Block3125369.block.hash,
        Block3125369.block.number,
        TestCoreConfigs.chainId
      )
      .value
    val typedTransaction01: RpcFullTransactionResponse = RpcFullTransactionResponse
      .from(
        Block3125369.transactionType01,
        1,
        Block3125369.block.hash,
        Block3125369.block.number,
        TestCoreConfigs.chainId
      )
      .value
    private lazy val typedTransaction02 = RpcFullTransactionResponse
      .from(
        Block3125369.transactionType02,
        2,
        Block3125369.block.hash,
        Block3125369.block.number,
        TestCoreConfigs.chainId
      )
      .value
    val queuedTxNonce2: RpcFullTransactionResponse = getTransaction(Nonce(0x2))
    val queuedTxNonce3: RpcFullTransactionResponse = getTransaction(Nonce(0x3))

    object txpool_content {
      val RequestBody: JArray = JArray(Nil)

      object Base {
        val CallResponse: GetContentResponse = GetContentResponse(
          pending = Map(
            legacyTransaction.from -> Map(
              legacyTransaction.nonce -> TxPoolController.toPooledTransaction(legacyTransaction)
            ),
            typedTransaction01.from -> Map(
              typedTransaction01.nonce -> TxPoolController.toPooledTransaction(typedTransaction01)
            ),
            typedTransaction02.from -> Map(
              typedTransaction02.nonce -> TxPoolController.toPooledTransaction(typedTransaction02)
            )
          ),
          queued = Map(
            templateSender -> Map(
              queuedTxNonce2.nonce -> TxPoolController.toPooledTransaction(queuedTxNonce2),
              queuedTxNonce3.nonce -> TxPoolController.toPooledTransaction(queuedTxNonce3)
            )
          )
        )

        val ExpectedResponse: Json =
          json"""
              {
                "pending": {
                  "0x9eab4b0fc468a7f5d46228bf5a76cb52370d068d": {
                    "438551": {
                      "blockHash": "0x0000000000000000000000000000000000000000000000000000000000000000",
                      "blockNumber": null,
                      "from": "0x9eab4b0fc468a7f5d46228bf5a76cb52370d068d",
                      "gas": "0xc350",
                      "gasPrice": "0x4a817c800",
                      "hash": "0xf3e33ba2cb400221476fa4025afd95a13907734c38a4a8dff4b7d860ee5adc8f",
                      "input": "0x",
                      "nonce": "0x6b117",
                      "r": "0x377e542cd9cd0a4414752a18d0862a5d6ced24ee6dba26b583cd85bc435b0ccf",
                      "s": "0x579fee4fd96ecf9a92ec450be3c9a139a687aa3c72c7e43cfac8c1feaf65c4ac",
                      "to": "0xc68e9954c7422f479e344faace70c692217ea05b",
                      "transactionIndex": null,
                      "type": "0x00",
                      "v": "0x9d",
                      "value": "0x91a9dcc39f65600"
                    }
                  },
                  "0x25331dc3a5024984c02d33bf6ae31f0bdce59f65": {
                    "438552": {
                      "accessList": [
                        {
                          "address": "0xc00e94cb662c3520282e6f5717214004a7f26888",
                          "storageKeys": [
                            "0x1",
                            "0x2"
                          ]
                        }
                      ],
                      "blockHash": "0x0000000000000000000000000000000000000000000000000000000000000000",
                      "blockNumber": null,
                      "from": "0x25331dc3a5024984c02d33bf6ae31f0bdce59f65",
                      "gas": "0xc350",
                      "gasPrice": "0x4a817c800",
                      "hash": "0x7c622d8ae62447903db4196bf3a84ae6d74a43cd074251a44ee2287c834f64dc",
                      "input": "0x",
                      "nonce": "0x6b118",
                      "r": "0xa70267341ba0b33f7e6f122080aa767d52ba4879776b793c35efec31dc70778d",
                      "s": "0x3f66ed7f0197627cbedfe80fd8e525e8bc6c5519aae7955e7493591dcdf1d6d2",
                      "to": "0x19c5a95eeae4446c5d24363eab4355157e4f828b",
                      "transactionIndex": null,
                      "type": "0x01",
                      "value": "0x33b553fc6e01c600"
                    }
                  },
                  "0xf1c0b5f1a701668a57e7c31fbcbd9a40ce515432": {
                    "438553": {
                      "accessList": [],
                      "blockHash": "0x0000000000000000000000000000000000000000000000000000000000000000",
                      "blockNumber": null,
                      "from": "0xf1c0b5f1a701668a57e7c31fbcbd9a40ce515432",
                      "gas": "0xc350",
                      "hash": "0x1d9d08ec01e969cdea9f646102ece112a3fd1af0887ef7019417fd8df826fd07",
                      "input": "0x",
                      "maxFeePerGas": "0x4a817c800",
                      "maxPriorityFeePerGas": "0x2540be400",
                      "nonce": "0x6b119",
                      "r": "0xbeb8226bdb90216ca29967871a6663b56bdd7b86cf3788796b52fd1ea3606698",
                      "s": "0x2446994156bc1780cb5806e730b171b38307d5de5b9b0d9ad1f9de82e00316b5",
                      "to": "0x3435be928d783b7c48a2c3109cba0d97d680747a",
                      "transactionIndex": null,
                      "type": "0x02",
                      "value": "0x181877a9a406710"
                    }
                  }
                },
                "queued": {
                  "0x0000000000000000000000000000000000123456": {
                    "2": {
                      "blockHash": "0x0000000000000000000000000000000000000000000000000000000000000000",
                      "blockNumber": null,
                      "from": "0x0000000000000000000000000000000000123456",
                      "gas": "0xc350",
                      "gasPrice": "0x4a817c800",
                      "hash": "0x4f78313233",
                      "input": "0x",
                      "nonce": "0x2",
                      "r": "0x7b",
                      "s": "0x1c8",
                      "to": null,
                      "transactionIndex": null,
                      "type": "0x00",
                      "v": "0x0",
                      "value": "0x75bcd15"
                    },
                    "3": {
                      "blockHash": "0x0000000000000000000000000000000000000000000000000000000000000000",
                      "blockNumber": null,
                      "from": "0x0000000000000000000000000000000000123456",
                      "gas": "0xc350",
                      "gasPrice": "0x4a817c800",
                      "hash": "0x4f78313233",
                      "input": "0x",
                      "nonce": "0x3",
                      "r": "0x7b",
                      "s": "0x1c8",
                      "to": null,
                      "transactionIndex": null,
                      "type": "0x00",
                      "v": "0x0",
                      "value": "0x75bcd15"
                    }
                  }
                }
              }
              """
      }

      object NoPendingTransactions {
        val CallResponse: GetContentResponse = GetContentResponse(
          pending = Map(
            legacyTransaction.from -> Map(
              legacyTransaction.nonce -> TxPoolController.toPooledTransaction(legacyTransaction)
            )
          ),
          queued = Map()
        )

        val ExpectedResponse: Json =
          json"""
          {
            "pending": {
              "0x9eab4b0fc468a7f5d46228bf5a76cb52370d068d": {
                "438551": {
                  "blockHash": "0x0000000000000000000000000000000000000000000000000000000000000000",
                  "blockNumber": null,
                  "from": "0x9eab4b0fc468a7f5d46228bf5a76cb52370d068d",
                  "gas": "0xc350",
                  "gasPrice": "0x4a817c800",
                  "hash": "0xf3e33ba2cb400221476fa4025afd95a13907734c38a4a8dff4b7d860ee5adc8f",
                  "input": "0x",
                  "nonce": "0x6b117",
                  "r": "0x377e542cd9cd0a4414752a18d0862a5d6ced24ee6dba26b583cd85bc435b0ccf",
                  "s": "0x579fee4fd96ecf9a92ec450be3c9a139a687aa3c72c7e43cfac8c1feaf65c4ac",
                  "to": "0xc68e9954c7422f479e344faace70c692217ea05b",
                  "transactionIndex": null,
                  "type": "0x00",
                  "v": "0x9d",
                  "value": "0x91a9dcc39f65600"
                }
              }
            },
            "queued": {}
          }
          """

      }

      object NoQueuedTransactions {
        val CallResponse: GetContentResponse = GetContentResponse(
          pending = Map(),
          queued = Map(
            legacyTransaction.from -> Map(
              legacyTransaction.nonce -> TxPoolController.toPooledTransaction(legacyTransaction)
            )
          )
        )

        val ExpectedResponse: Json =
          json"""
            {
              "pending": {},
              "queued": {
                "0x9eab4b0fc468a7f5d46228bf5a76cb52370d068d": {
                  "438551": {
                    "blockHash": "0x0000000000000000000000000000000000000000000000000000000000000000",
                    "blockNumber": null,
                    "from": "0x9eab4b0fc468a7f5d46228bf5a76cb52370d068d",
                    "gas": "0xc350",
                    "gasPrice": "0x4a817c800",
                    "hash": "0xf3e33ba2cb400221476fa4025afd95a13907734c38a4a8dff4b7d860ee5adc8f",
                    "input": "0x",
                    "nonce": "0x6b117",
                    "r": "0x377e542cd9cd0a4414752a18d0862a5d6ced24ee6dba26b583cd85bc435b0ccf",
                    "s": "0x579fee4fd96ecf9a92ec450be3c9a139a687aa3c72c7e43cfac8c1feaf65c4ac",
                    "to": "0xc68e9954c7422f479e344faace70c692217ea05b",
                    "transactionIndex": null,
                    "type": "0x00",
                    "v": "0x9d",
                    "value": "0x91a9dcc39f65600"
                  }
                }
              }
            }
            """

      }

      object NoTransactions {
        val CallResponse: GetContentResponse = GetContentResponse(
          pending = Map(),
          queued = Map()
        )

        val ExpectedResponse: Json =
          json"""
            {
              "pending": {},
              "queued": {}
            }
            """
      }
    }

    private lazy val templateTransaction = LegacyTransaction(
      nonce = Nonce(BigInt("0")),
      gasPrice = BigInt("20000000000"),
      gasLimit = BigInt("50000"),
      receivingAddress = None,
      value = BigInt("123456789"),
      payload = ByteString.empty
    )

    def getTransaction(newNonce: Nonce): RpcFullTransactionResponse =
      RpcLegacyTransactionResponse
        .from(
          templateTransaction,
          TransactionHash(ByteString.fromString("Ox123")),
          templateSender,
          ECDSASignature(BigInt("123"), BigInt("456"), 0.toByte),
          null,
          BlockHash(ByteString.empty),
          BlockNumber(null)
        )
        .copy(nonce = newNonce)
  }

}
