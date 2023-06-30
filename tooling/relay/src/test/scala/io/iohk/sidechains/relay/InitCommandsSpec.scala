package io.iohk.sidechains.relay

import cats.effect.IO
import cats.implicits.none
import io.circe.{Decoder, Json}
import io.iohk.sidechains.relay.RpcFormats.{
  CommitteeHandoverSignatures,
  CrossChainSignaturesEntry,
  EpochToUpload,
  GetSignaturesResponse,
  SidechainParams,
  SidechainStatusPartial,
  StatusPartial
}
import munit.CatsEffectSuite

class InitCommandsSpec extends CatsEffectSuite {
  test("Don't init when signatures to upload succeeds") {
    val rpc = TestRpcClient(signaturesToUploadResult = Right(List.empty))
    (for {
      commands <- new InitCommands(rpc, Some("/the/path/to.skey")).getCtlCommand
    } yield commands).assertEquals(none)
  }

  test("Don't init when signing key isn't set") {
    val rpc = TestRpcClient(
      signaturesToUploadResult = Left("Could not find any committee nft. Is the sidechain initialized?"),
      epochsData = Map(
        4097L -> signaturesResponse(committeeHandover4097),
        4098L -> signaturesResponse(committeeHandover4098),
        4099L -> signaturesResponse(committeeHandover4099),
        4100L -> signaturesResponse(committeeHandover4100)
      )
    )
    (for {
      commands <- new InitCommands(rpc, None).getCtlCommand
    } yield commands).assertEquals(none)
  }

  test("Don't init when there no signatures in the recent epochs") {
    val rpc = TestRpcClient(
      signaturesToUploadResult = Left("Could not find any committee nft. Is the sidechain initialized?"),
      epochsData = Map(
        4097L -> signaturesResponse(committeeHandover4097),
        4098L -> signaturesResponse(committeeHandover4098)
      )
    )
    (for {
      commands <- new InitCommands(rpc, Some("/the/path/to.skey")).getCtlCommand
    } yield commands).assertEquals(none)
  }

  test("init - use committee from epoch 4100 and epoch 4099 parameters") {
    val rpc = TestRpcClient(
      signaturesToUploadResult = Left("Could not find any committee nft. Is the sidechain initialized?"),
      epochsData = Map(
        4097L -> signaturesResponse(committeeHandover4097),
        4098L -> signaturesResponse(committeeHandover4098),
        4099L -> signaturesResponse(committeeHandover4099),
        4100L -> signaturesResponse(committeeHandover4100),
        4101L -> signaturesResponse(committeeHandover4101)
      )
    )
    (for {
      commands <- new InitCommands(rpc, Some("/the/path/to.skey")).getCtlCommand
    } yield commands).assertEquals(
      Some(
        List(
          "sidechain-main-cli",
          "init",
          "--genesis-committee-hash-utxo",
          "the-genesis-utxo",
          "--sidechain-id",
          "225",
          "--sidechain-genesis-hash",
          "the-genesis-hash",
          "--threshold-numerator",
          "2",
          "--threshold-denominator",
          "3",
          "--sidechain-epoch",
          "4099",
          "--committee-pub-key",
          "02c856df52f7ee7b9c341f34617bf9397736676754a868c8e7f6acab90f0ae6579",
          "--committee-pub-key",
          "02eb3a752840b36e0583b5dcc7e03d1d963f76013e3b2b8399025067d532ec88aa",
          "--committee-pub-key",
          "03f5efb84c1e6581dde7abe7413b7768feb0cb80e2c62188cb5e13d885e3f3f5f4",
          "--payment-signing-key-file",
          "/the/path/to.skey"
        )
      )
    )
  }

  test(
    "init - use committee from epoch with enough signatures when cannot get data for the epoch before it"
  ) {
    val rpc = TestRpcClient(
      signaturesToUploadResult = Left("Could not find any committee nft. Is the sidechain initialized?"),
      epochsData = Map(4100L -> signaturesResponse(committeeHandover4100))
    )
    (for {
      commands <- new InitCommands(rpc, Some("/the/path/to.skey")).getCtlCommand
    } yield commands).assertEquals(
      Some(
        List(
          "sidechain-main-cli",
          "init",
          "--genesis-committee-hash-utxo",
          "the-genesis-utxo",
          "--sidechain-id",
          "225",
          "--sidechain-genesis-hash",
          "the-genesis-hash",
          "--threshold-numerator",
          "2",
          "--threshold-denominator",
          "3",
          "--sidechain-epoch",
          "4099",
          "--committee-pub-key",
          "02c856df52f7ee7b9c341f34617bf9397736676754a868c8e7f6acab90f0ae6579",
          "--committee-pub-key",
          "02eb3a752840b36e0583b5dcc7e03d1d963f76013e3b2b8399025067d532ec88aa",
          "--committee-pub-key",
          "03f5efb84c1e6581dde7abe7413b7768feb0cb80e2c62188cb5e13d885e3f3f5f4",
          "--payment-signing-key-file",
          "/the/path/to.skey"
        )
      )
    )
  }

  private def signaturesResponse(committeeHandover: CommitteeHandoverSignatures) =
    GetSignaturesResponse(sidechainParams, committeeHandover, List.empty)

  private val sidechainParams = SidechainParams(
    chainId = "0xE1",
    genesisHash = "0xthe-genesis-hash",
    genesisUtxo = "the-genesis-utxo",
    thresholdNumerator = 2,
    thresholdDenominator = 3
  )

  // Chain starts in epoch 4099, there are no signatures in 4097 and 4098
  private val committeeHandover4097 = CommitteeHandoverSignatures(
    nextCommitteePubKeys = List(
      "0xeb3a752840b36e0583b5dcc7e03d1d963f76013e3b2b8399025067d532ec88aa5a1953ceb82b69262918a2986eea8e90b2fd90f872fc35917d1837c4f2c2b568",
      "0xf5efb84c1e6581dde7abe7413b7768feb0cb80e2c62188cb5e13d885e3f3f5f47b2f56bcb62675c25c2b75e4664a364c6967e3ad45322fd54aaa7b6ab7dd67dd",
      "0x37613e6f1de82410503ff3ff2c0593c8e67e7a15533be04ea59336cb30b8e6dd111aebb39915d585f6671ba78fe6dcb9cffb601d8689309ff4da6479e8745204"
    ),
    previousMerkleRootHash = None,
    signatures = List.empty
  )

  private val committeeHandover4098 = CommitteeHandoverSignatures(
    nextCommitteePubKeys = List(
      "0xeb3a752840b36e0583b5dcc7e03d1d963f76013e3b2b8399025067d532ec88aa5a1953ceb82b69262918a2986eea8e90b2fd90f872fc35917d1837c4f2c2b568",
      "0x37613e6f1de82410503ff3ff2c0593c8e67e7a15533be04ea59336cb30b8e6dd111aebb39915d585f6671ba78fe6dcb9cffb601d8689309ff4da6479e8745204",
      "0x6866e64f2bd8497bcc459b1a17f878999547393071d9f4dd3a27efbff78863b12285bc4673df6349d2eb3881d0e224759dbccc34c230c34d6172944d71c89b2c"
    ),
    previousMerkleRootHash = None,
    signatures = List.empty
  )

  private val committeeHandover4099 = CommitteeHandoverSignatures(
    nextCommitteePubKeys = List(
      "0xc856df52f7ee7b9c341f34617bf9397736676754a868c8e7f6acab90f0ae6579aef4f7eb324ca3eb6351da1603d065035e5ec1690d7752b386b4228f23eace3a",
      "0xeb3a752840b36e0583b5dcc7e03d1d963f76013e3b2b8399025067d532ec88aa5a1953ceb82b69262918a2986eea8e90b2fd90f872fc35917d1837c4f2c2b568",
      "0xf5efb84c1e6581dde7abe7413b7768feb0cb80e2c62188cb5e13d885e3f3f5f47b2f56bcb62675c25c2b75e4664a364c6967e3ad45322fd54aaa7b6ab7dd67dd"
    ),
    previousMerkleRootHash = None,
    signatures = List(
      CrossChainSignaturesEntry(
        "0xeb3a752840b36e0583b5dcc7e03d1d963f76013e3b2b8399025067d532ec88aa5a1953ceb82b69262918a2986eea8e90b2fd90f872fc35917d1837c4f2c2b568",
        "0xaec949b17c4a99e9d34d4b6a5206ddd81f8e2c882857ad73940e13328034f2c527d2a6d65d94c6f874d9104797f0db73a5f6bec1f34f99ccfe8dedca77de13981c"
      ),
      CrossChainSignaturesEntry(
        "0x37613e6f1de82410503ff3ff2c0593c8e67e7a15533be04ea59336cb30b8e6dd111aebb39915d585f6671ba78fe6dcb9cffb601d8689309ff4da6479e8745204",
        "0x207020c075d5a221a82136c607c12ca562f138113ecfdeceb7c03371194161b90d115e108eeb1475953b5234920feb8b90e5a28c6829fef02c8fe9e563de235f1b"
      )
    )
  )

  private val committeeHandover4100 = CommitteeHandoverSignatures(
    nextCommitteePubKeys = List(
      "0xeb3a752840b36e0583b5dcc7e03d1d963f76013e3b2b8399025067d532ec88aa5a1953ceb82b69262918a2986eea8e90b2fd90f872fc35917d1837c4f2c2b568",
      "0xf5efb84c1e6581dde7abe7413b7768feb0cb80e2c62188cb5e13d885e3f3f5f47b2f56bcb62675c25c2b75e4664a364c6967e3ad45322fd54aaa7b6ab7dd67dd",
      "0x6866e64f2bd8497bcc459b1a17f878999547393071d9f4dd3a27efbff78863b12285bc4673df6349d2eb3881d0e224759dbccc34c230c34d6172944d71c89b2c"
    ),
    previousMerkleRootHash = None,
    signatures = List(
      CrossChainSignaturesEntry(
        "0xc856df52f7ee7b9c341f34617bf9397736676754a868c8e7f6acab90f0ae6579aef4f7eb324ca3eb6351da1603d065035e5ec1690d7752b386b4228f23eace3a",
        "0x24d231af0c8c680b00ab41ea44539163e2223a92a3fa5b1266947981d5c593b04bb7c43ce067a9a76643d76e3ec1320d6b404a8e0481cdd2159f3efbdce1953d1c"
      ),
      CrossChainSignaturesEntry(
        "0xeb3a752840b36e0583b5dcc7e03d1d963f76013e3b2b8399025067d532ec88aa5a1953ceb82b69262918a2986eea8e90b2fd90f872fc35917d1837c4f2c2b568",
        "0xe01a483881af5ffdec1931b26ecee1b10675b22fdd7d9451b0cab25d13c040a875b24df81576695fa8b3a2ee1130c57e2e2c167faa5db0c70da0cafa147203bf1b"
      ),
      CrossChainSignaturesEntry(
        "0xf5efb84c1e6581dde7abe7413b7768feb0cb80e2c62188cb5e13d885e3f3f5f47b2f56bcb62675c25c2b75e4664a364c6967e3ad45322fd54aaa7b6ab7dd67dd",
        "0x67946314918ab4db0b972d1fc5fb0e152a3dfd95dbcfc45d8ebecb7220b79e0b2696367ac17bafd76951ee6b2dcd6f48ea966cc56be7b2732b50483bd89ca61c1b"
      )
    )
  )

  private val committeeHandover4101 = CommitteeHandoverSignatures(
    nextCommitteePubKeys = List(
      "0xeb3a752840b36e0583b5dcc7e03d1d963f76013e3b2b8399025067d532ec88aa5a1953ceb82b69262918a2986eea8e90b2fd90f872fc35917d1837c4f2c2b568",
      "0xf5efb84c1e6581dde7abe7413b7768feb0cb80e2c62188cb5e13d885e3f3f5f47b2f56bcb62675c25c2b75e4664a364c6967e3ad45322fd54aaa7b6ab7dd67dd",
      "0x6866e64f2bd8497bcc459b1a17f878999547393071d9f4dd3a27efbff78863b12285bc4673df6349d2eb3881d0e224759dbccc34c230c34d6172944d71c89b2c"
    ),
    previousMerkleRootHash = None,
    signatures = List(
      CrossChainSignaturesEntry(
        "0xc856df52f7ee7b9c341f34617bf9397736676754a868c8e7f6acab90f0ae6579aef4f7eb324ca3eb6351da1603d065035e5ec1690d7752b386b4228f23eace3a",
        "0xaad231af0c8c680b00ab41ea44539163e2223a92a3fa5b1266947981d5c593b04bb7c43ce067a9a76643d76e3ec1320d6b404a8e0481cdd2159f3efbdce1953d1c"
      ),
      CrossChainSignaturesEntry(
        "0xeb3a752840b36e0583b5dcc7e03d1d963f76013e3b2b8399025067d532ec88aa5a1953ceb82b69262918a2986eea8e90b2fd90f872fc35917d1837c4f2c2b568",
        "0xaa1a483881af5ffdec1931b26ecee1b10675b22fdd7d9451b0cab25d13c040a875b24df81576695fa8b3a2ee1130c57e2e2c167faa5db0c70da0cafa147203bf1b"
      ),
      CrossChainSignaturesEntry(
        "0x37613e6f1de82410503ff3ff2c0593c8e67e7a15533be04ea59336cb30b8e6dd111aebb39915d585f6671ba78fe6dcb9cffb601d8689309ff4da6479e8745204",
        "0x207020c075d5a221a82136c607c12ca562f138113ecfdeceb7c03371194161b90d115e108eeb1475953b5234920feb8b90e5a28c6829fef02c8fe9e563de235f1b"
      )
    )
  )
}

final case class TestRpcClient(
    signaturesToUploadResult: Either[String, List[EpochToUpload]] = Right(List.empty),
    epochsData: Map[Long, GetSignaturesResponse] = Map.empty
) extends RpcClient {
  override def call[T: Decoder](method: String, parameters: List[Json]): IO[T] = method match {
    case "sidechain_getSignaturesToUpload" =>
      signaturesToUploadResult match {
        case Right(signatures) => IO(signatures.asInstanceOf[T])
        case Left(message)     => IO.raiseError(new Exception(s"TestRpcClient failure: $message"))
      }
    case "sidechain_getStatus" =>
      IO.pure(StatusPartial(SidechainStatusPartial(epochsData.keys.max + 1L)).asInstanceOf[T])
    case "sidechain_getEpochSignatures" =>
      IO {
        val epoch = parameters.head.as[Long].fold(e => throw new Exception(e), identity)
        epochsData.getOrElse(epoch, throw new Exception(s"unexpected epoch")).asInstanceOf[T]
      }
    case method => IO.raiseError(new Exception(s"Unknown method: $method"))
  }
}
