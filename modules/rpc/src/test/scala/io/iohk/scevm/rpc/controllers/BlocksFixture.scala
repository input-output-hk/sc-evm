package io.iohk.scevm.rpc.controllers

import io.circe.Json
import io.circe.literal.JsonStringContext
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignature}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{
  AccessListItem,
  Address,
  BlockHash,
  BlockNumber,
  LegacyTransaction,
  Nonce,
  ObftBlock,
  ObftBody,
  ObftHeader,
  SignedTransaction,
  Slot,
  TransactionType01,
  TransactionType02
}
import io.iohk.scevm.rpc.domain.RpcBlockResponse
import io.iohk.scevm.testing.TestCoreConfigs
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp

trait BlocksFixture {
  trait FixtureBlock {
    val header: ObftHeader
    val body: ObftBody

    final def block: ObftBlock = ObftBlock(header, body)
  }

  object Block123 extends FixtureBlock {
    val legacyTransactionWithoutReceivingAddress: SignedTransaction = SignedTransaction(
      tx = LegacyTransaction(
        nonce = Nonce(BigInt("438551")),
        gasPrice = BigInt("20000000000"),
        gasLimit = BigInt("50000"),
        receivingAddress = None,
        value = BigInt("656010196207162880"),
        payload = ByteString.empty
      ),
      pointSign = 0x9d.toByte,
      signatureRandom = Hex.decodeUnsafe("377e542cd9cd0a4414752a18d0862a5d6ced24ee6dba26b583cd85bc435b0ccf"),
      signature = Hex.decodeUnsafe("579fee4fd96ecf9a92ec450be3c9a139a687aa3c72c7e43cfac8c1feaf65c4ac")
    )

    override val header: ObftHeader = ObftHeader(
      parentHash = BlockHash(Hex.decodeUnsafe("8345d132564b3660aa5f27c9415310634b50dbc92579c65a0825d9a255227a71")),
      number = BlockNumber(123),
      slotNumber = Slot(123 * 2),
      beneficiary = Address("df7d7e053933b5cc24372f878c90e62dadad5d42"),
      stateRoot = Hex.decodeUnsafe("087f96537eba43885ab563227262580b27fc5e6516db79a6fc4d3bcd241dda67"),
      transactionsRoot = Hex.decodeUnsafe("8ae451039a8bf403b899dcd23252d94761ddd23b88c769d9b7996546edc47fac"),
      receiptsRoot = Hex.decodeUnsafe("8b472d8d4d39bae6a5570c2a42276ed2d6a56ac51a1a356d5b17c5564d01fd5d"),
      logsBloom = Hex.decodeUnsafe("0" * 512),
      gasLimit = 4699996,
      gasUsed = 84000,
      unixTimestamp = 1486131165.millisToTs,
      publicSigningKey = ECDSA.PublicKey.Zero,
      signature = ECDSASignature(1, 1, 1)
    )

    override val body: ObftBody = ObftBody(Seq(legacyTransactionWithoutReceivingAddress))
  }

  object Block3125369 extends FixtureBlock {

    val legacyTransaction: SignedTransaction = SignedTransaction(
      tx = LegacyTransaction(
        nonce = Nonce(BigInt("438551")),
        gasPrice = BigInt("20000000000"),
        gasLimit = BigInt("50000"),
        receivingAddress = Address(Hex.decodeUnsafe("c68e9954c7422f479e344faace70c692217ea05b")),
        value = BigInt("656010196207162880"),
        payload = ByteString.empty
      ),
      pointSign = 0x9d.toByte,
      signatureRandom = Hex.decodeUnsafe("377e542cd9cd0a4414752a18d0862a5d6ced24ee6dba26b583cd85bc435b0ccf"),
      signature = Hex.decodeUnsafe("579fee4fd96ecf9a92ec450be3c9a139a687aa3c72c7e43cfac8c1feaf65c4ac")
    )

    val transactionType01: SignedTransaction = SignedTransaction(
      tx = TransactionType01(
        chainId = BigInt(1),
        nonce = Nonce(BigInt("438552")),
        gasPrice = BigInt("20000000000"),
        gasLimit = BigInt("50000"),
        receivingAddress = Address(Hex.decodeUnsafe("19c5a95eeae4446c5d24363eab4355157e4f828b")),
        value = BigInt("3725976610361427456"),
        payload = ByteString.empty,
        accessList = List(
          AccessListItem(
            address = Address(Hex.decodeUnsafe("c00e94cb662c3520282e6f5717214004a7f26888")),
            storageKeys = List(BigInt(1), BigInt(2))
          )
        )
      ),
      pointSign = 0x0.toByte,
      signatureRandom = Hex.decodeUnsafe("a70267341ba0b33f7e6f122080aa767d52ba4879776b793c35efec31dc70778d"),
      signature = Hex.decodeUnsafe("3f66ed7f0197627cbedfe80fd8e525e8bc6c5519aae7955e7493591dcdf1d6d2")
    )

    val transactionType02: SignedTransaction = SignedTransaction(
      tx = TransactionType02(
        chainId = BigInt(1),
        nonce = Nonce(BigInt("438553")),
        maxPriorityFeePerGas = BigInt("10000000000"),
        maxFeePerGas = BigInt("20000000000"),
        gasLimit = BigInt("50000"),
        receivingAddress = Address(Hex.decodeUnsafe("3435be928d783b7c48a2c3109cba0d97d680747a")),
        value = BigInt("108516826677274384"),
        payload = ByteString.empty,
        accessList = List.empty
      ),
      pointSign = 0x0.toByte,
      signatureRandom = Hex.decodeUnsafe("beb8226bdb90216ca29967871a6663b56bdd7b86cf3788796b52fd1ea3606698"),
      signature = Hex.decodeUnsafe("2446994156bc1780cb5806e730b171b38307d5de5b9b0d9ad1f9de82e00316b5")
    )

    override val header: ObftHeader = ObftHeader(
      parentHash = BlockHash(Hex.decodeUnsafe("8345d132564b3660aa5f27c9415310634b50dbc92579c65a0825d9a255227a71")),
      number = BlockNumber(3125369),
      slotNumber = Slot(3125369 * 2),
      beneficiary = Address("df7d7e053933b5cc24372f878c90e62dadad5d42"),
      stateRoot = Hex.decodeUnsafe("087f96537eba43885ab563227262580b27fc5e6516db79a6fc4d3bcd241dda67"),
      transactionsRoot = Hex.decodeUnsafe("8ae451039a8bf403b899dcd23252d94761ddd23b88c769d9b7996546edc47fac"),
      receiptsRoot = Hex.decodeUnsafe("8b472d8d4d39bae6a5570c2a42276ed2d6a56ac51a1a356d5b17c5564d01fd5d"),
      logsBloom = Hex.decodeUnsafe("0" * 512),
      gasLimit = 4699996,
      gasUsed = 84000,
      unixTimestamp = 1486131165.millisToTs,
      publicSigningKey = ECDSA.PublicKey.Zero,
      signature = ECDSASignature(1, 1, 1)
    )
    override val body: ObftBody = ObftBody(
      List(
        legacyTransaction,
        transactionType01,
        transactionType02
      )
    )
  }

  val expectedFullRpcBlockResponseForBlock3125369: RpcBlockResponse =
    RpcBlockResponse.from(Block3125369.block, TestCoreConfigs.chainId, fullTxs = true).toOption.get
  val expectedNotFullRpcBlockResponseForBlock3125369: RpcBlockResponse =
    RpcBlockResponse.from(Block3125369.block, TestCoreConfigs.chainId, fullTxs = false).toOption.get

  val expectedOutcomeForBlock3125369: Json = json"""
    {
      "difficulty": "0x0",
      "totalDifficulty": "0x0",
      "extraData": "0x0000000000000000000000000000000000000000",
      "gasLimit": "0x47b75c",
      "gasUsed": "0x14820",
      "logsBloom": "0x00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
      "number": "0x2fb079",
      "parentHash": "0x8345d132564b3660aa5f27c9415310634b50dbc92579c65a0825d9a255227a71",
      "receiptsRoot": "0x8b472d8d4d39bae6a5570c2a42276ed2d6a56ac51a1a356d5b17c5564d01fd5d",
      "sha3Uncles": "0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",
      "size": "0x3fe",
      "stateRoot": "0x087f96537eba43885ab563227262580b27fc5e6516db79a6fc4d3bcd241dda67",
      "timestamp": "0x16ad33",
      "miner": "0xdf7d7e053933b5cc24372f878c90e62dadad5d42",
      "transactions":
      [
        {
          "type": "0x00",
          "nonce": "0x6b117",
          "hash": "0xf3e33ba2cb400221476fa4025afd95a13907734c38a4a8dff4b7d860ee5adc8f",
          "from": "0x9eab4b0fc468a7f5d46228bf5a76cb52370d068d",
          "to": "0xc68e9954c7422f479e344faace70c692217ea05b",
          "gas": "0xc350",
          "value": "0x91a9dcc39f65600",
          "input": "0x",
          "gasPrice": "0x4a817c800",
          "v": "0x9d",
          "r": "0x377e542cd9cd0a4414752a18d0862a5d6ced24ee6dba26b583cd85bc435b0ccf",
          "s": "0x579fee4fd96ecf9a92ec450be3c9a139a687aa3c72c7e43cfac8c1feaf65c4ac",
          "transactionIndex": "0x0",
          "blockHash": "0xe7cc25a984407887b9ea630bc4ea7727020ce5d6c262da485fb9bc42c0df2813",
          "blockNumber": "0x2fb079"
        },
        {
          "type": "0x01",
          "nonce": "0x6b118",
          "hash": "0x7c622d8ae62447903db4196bf3a84ae6d74a43cd074251a44ee2287c834f64dc",
          "from": "0x25331dc3a5024984c02d33bf6ae31f0bdce59f65",
          "to": "0x19c5a95eeae4446c5d24363eab4355157e4f828b",
          "gas": "0xc350",
          "value": "0x33b553fc6e01c600",
          "input": "0x",
          "gasPrice": "0x4a817c800",
          "accessList":
          [
            {
              "address": "0xc00e94cb662c3520282e6f5717214004a7f26888",
              "storageKeys":
              [
                "0x1",
                "0x2"
              ]
            }
          ],
          "chainId": "0x1",
          "yParity": "0x00",
          "r": "0xa70267341ba0b33f7e6f122080aa767d52ba4879776b793c35efec31dc70778d",
          "s": "0x3f66ed7f0197627cbedfe80fd8e525e8bc6c5519aae7955e7493591dcdf1d6d2",
          "transactionIndex": "0x1",
          "blockHash": "0xe7cc25a984407887b9ea630bc4ea7727020ce5d6c262da485fb9bc42c0df2813",
          "blockNumber": "0x2fb079"
        },
        {
          "type": "0x02",
          "nonce": "0x6b119",
          "hash": "0x1d9d08ec01e969cdea9f646102ece112a3fd1af0887ef7019417fd8df826fd07",
          "from": "0xf1c0b5f1a701668a57e7c31fbcbd9a40ce515432",
          "to": "0x3435be928d783b7c48a2c3109cba0d97d680747a",
          "gas": "0xc350",
          "value": "0x181877a9a406710",
          "input": "0x",
          "maxPriorityFeePerGas": "0x2540be400",
          "maxFeePerGas": "0x4a817c800",
          "accessList":
          [],
          "chainId": "0x1",
          "yParity": "0x00",
          "r": "0xbeb8226bdb90216ca29967871a6663b56bdd7b86cf3788796b52fd1ea3606698",
          "s": "0x2446994156bc1780cb5806e730b171b38307d5de5b9b0d9ad1f9de82e00316b5",
          "transactionIndex": "0x2",
          "blockHash": "0xe7cc25a984407887b9ea630bc4ea7727020ce5d6c262da485fb9bc42c0df2813",
          "blockNumber": "0x2fb079"
        }
      ],
      "transactionsRoot": "0x8ae451039a8bf403b899dcd23252d94761ddd23b88c769d9b7996546edc47fac",
      "uncles": [],
      "hash": "0xe7cc25a984407887b9ea630bc4ea7727020ce5d6c262da485fb9bc42c0df2813"
    }
    """

  val expectedLegacyTransactionSerialized: Json =
    json"""
      {
        "type": "0x00",
        "nonce": "0x6b117",
        "hash": "0xf3e33ba2cb400221476fa4025afd95a13907734c38a4a8dff4b7d860ee5adc8f",
        "from": "0x9eab4b0fc468a7f5d46228bf5a76cb52370d068d",
        "to": "0xc68e9954c7422f479e344faace70c692217ea05b",
        "gas": "0xc350",
        "value": "0x91a9dcc39f65600",
        "input": "0x",
        "gasPrice": "0x4a817c800",
        "v": "0x9d",
        "r": "0x377e542cd9cd0a4414752a18d0862a5d6ced24ee6dba26b583cd85bc435b0ccf",
        "s": "0x579fee4fd96ecf9a92ec450be3c9a139a687aa3c72c7e43cfac8c1feaf65c4ac",
        "transactionIndex": "0x0",
        "blockHash": "0xe7cc25a984407887b9ea630bc4ea7727020ce5d6c262da485fb9bc42c0df2813",
        "blockNumber": "0x2fb079"
      }
      """

  val expectedTransactionType01Serialized: Json =
    json"""
      {
        "type": "0x01",
        "nonce": "0x6b118",
        "hash": "0x7c622d8ae62447903db4196bf3a84ae6d74a43cd074251a44ee2287c834f64dc",
        "from": "0x25331dc3a5024984c02d33bf6ae31f0bdce59f65",
        "to": "0x19c5a95eeae4446c5d24363eab4355157e4f828b",
        "gas": "0xc350",
        "value": "0x33b553fc6e01c600",
        "input": "0x",
        "gasPrice": "0x4a817c800",
        "accessList": [
          {
            "address": "0xc00e94cb662c3520282e6f5717214004a7f26888",
            "storageKeys": [
              "0x1",
              "0x2"
            ]
          }
        ],
        "chainId": "0x1",
        "yParity": "0x00",
        "r": "0xa70267341ba0b33f7e6f122080aa767d52ba4879776b793c35efec31dc70778d",
        "s": "0x3f66ed7f0197627cbedfe80fd8e525e8bc6c5519aae7955e7493591dcdf1d6d2",
        "transactionIndex": "0x1",
        "blockHash": "0xe7cc25a984407887b9ea630bc4ea7727020ce5d6c262da485fb9bc42c0df2813",
        "blockNumber": "0x2fb079"
      }
      """

  val expectedTransactionType02Serialized: Json =
    json"""
        {
          "type": "0x02",
          "nonce": "0x6b119",
          "hash": "0x1d9d08ec01e969cdea9f646102ece112a3fd1af0887ef7019417fd8df826fd07",
          "from": "0xf1c0b5f1a701668a57e7c31fbcbd9a40ce515432",
          "to": "0x3435be928d783b7c48a2c3109cba0d97d680747a",
          "gas": "0xc350",
          "value": "0x181877a9a406710",
          "input": "0x",
          "maxPriorityFeePerGas": "0x2540be400",
          "maxFeePerGas": "0x4a817c800",
          "accessList": [],
          "chainId": "0x1",
          "yParity": "0x00",
          "r": "0xbeb8226bdb90216ca29967871a6663b56bdd7b86cf3788796b52fd1ea3606698",
          "s": "0x2446994156bc1780cb5806e730b171b38307d5de5b9b0d9ad1f9de82e00316b5",
          "transactionIndex": "0x2",
          "blockHash": "0xe7cc25a984407887b9ea630bc4ea7727020ce5d6c262da485fb9bc42c0df2813",
          "blockNumber": "0x2fb079"
        }
        """

  val expectedLegacyTransactionWithoutReceivingAddressSerialized: Json =
    json"""
      {
        "blockHash": "0x382adc9c96f71312aea1c4ee19e7e504310fe3904904a9384b01c5da1d01aa87",
        "blockNumber": "0x7b",
        "type": "0x00",
        "nonce": "0x6b117",
        "hash": "0x5a996d0c00a08333a8320da8bfae0ace28cd7f4a0dd61d56f93d340c77851c60",
        "from": "0x4adf3455026b5d55664ea76336ad2bdbe1a78cef",
        "to": null,
        "gas": "0xc350",
        "value": "0x91a9dcc39f65600",
        "input": "0x",
        "gasPrice": "0x4a817c800",
        "v": "0x9d",
        "r": "0x377e542cd9cd0a4414752a18d0862a5d6ced24ee6dba26b583cd85bc435b0ccf",
        "s": "0x579fee4fd96ecf9a92ec450be3c9a139a687aa3c72c7e43cfac8c1feaf65c4ac",
        "transactionIndex": "0x0"
      }
        """
}
