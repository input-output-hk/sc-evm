package io.iohk.scevm.rpc.armadillo

import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto._
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain._
import io.iohk.scevm.healthcheck.{HealthcheckResponse, HealthcheckResult}
import io.iohk.scevm.rpc.AlwaysNull
import io.iohk.scevm.rpc.controllers.DebugController.{TraceParameters, TraceTransactionResponse}
import io.iohk.scevm.rpc.controllers.EthBlocksController.GetBlockByHashResponse
import io.iohk.scevm.rpc.controllers.EthTransactionController.{GetLogsRequest, NestableTopicSearch, TopicSearch}
import io.iohk.scevm.rpc.controllers.EthVMController.{CallResponse, CallTransaction}
import io.iohk.scevm.rpc.controllers.EthWorldStateController.{SmartContractCode, StorageData}
import io.iohk.scevm.rpc.controllers.InspectController.{Block, GetChainResponse, Link}
import io.iohk.scevm.rpc.controllers.NetController.{ChainMode, GetNetworkInfoResponse, NetVersion}
import io.iohk.scevm.rpc.controllers.PersonalController._
import io.iohk.scevm.rpc.controllers.SanityController.{
  ChainConsistencyError,
  CheckChainConsistencyRequest,
  CheckChainConsistencyResponse
}
import io.iohk.scevm.rpc.controllers.TxPoolController._
import io.iohk.scevm.rpc.controllers.Web3Controller.ClientVersion
import io.iohk.scevm.rpc.controllers._
import io.iohk.scevm.rpc.domain.{
  RpcAccessListItemResponse,
  RpcBlockResponse,
  RpcFullTransactionResponse,
  TransactionRequest
}
import io.iohk.scevm.utils.NodeState
import org.json4s._
import sttp.tapir.codec.newtype.TapirCodecNewType
import sttp.tapir.{Schema, ValidationResult, Validator}

import java.time.Duration
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.Try

trait CommonRpcSchemas extends TapirCodecNewType {

  implicit lazy val schemaForJString: Schema[JString]   = Schema.derived
  implicit lazy val schemaForJDouble: Schema[JDouble]   = Schema.derived
  implicit lazy val schemaForJDecimal: Schema[JDecimal] = Schema.derived
  implicit lazy val schemaForJLong: Schema[JLong]       = Schema.derived
  implicit lazy val schemaForJInt: Schema[JInt]         = Schema.derived
  implicit lazy val schemaForJBool: Schema[JBool]       = Schema.derived
  implicit lazy val schemaForJField: Schema[JField]     = Schema.derived
  implicit lazy val schemaForJObject: Schema[JObject]   = Schema.derived
  implicit lazy val schemaForJArray: Schema[JArray]     = Schema.derived
  implicit lazy val schemaForJSet: Schema[JSet]         = Schema.derived
  implicit lazy val schemaJValue: Schema[JValue]        = Schema.derived

  val bigIntFromHexString: Schema[BigInt] = Schema.schemaForString
    .validate(Validator.pattern("^0x[a-f0-9]+$"))
    .map(v => Try(BigInt(v, 16)).toOption)(n => "0x" + Hex.toHexString(n.toByteArray))

  implicit val schemaForToken: Schema[Token] = bigIntFromHexString
    .validate(Validator.positiveOrZero)
    .map(v => Some(Token(v)))(_.value)

  implicit val schemaForEthBlockParam: Schema[EthBlockParam]           = Schema.derived
  implicit val schemaForExtendedBlockParam: Schema[ExtendedBlockParam] = Schema.derived
  implicit lazy val schemaForBlockParamByNumber: Schema[BlockParam.ByNumber] =
    bigIntFromHexString
      .validate(Validator.positiveOrZero)
      .map(i => Some(BlockParam.ByNumber(BlockNumber(i))))(_.n.value)
  implicit lazy val schemaForBlockParam: Schema[BlockParam] = Schema.derived
  implicit val schemaForByteString: Schema[ByteString] = Schema.schemaForByteArray
    .as[ByteString]
    .description("String representing a hex-encoded array of bytes")
  implicit val schemaForBlockHash: Schema[BlockHash] = schemaForByteString.map { byteString =>
    Some(BlockHash(byteString))
  }(_.byteString)
  implicit val schemaForTransactionHash: Schema[TransactionHash] = schemaForByteString.map { byteString =>
    Some(TransactionHash(byteString))
  }(_.byteString)

  implicit val schemaForTransactionIndex: Schema[TransactionIndex] =
    Schema.schemaForBigInt.validate(Validator.positiveOrZero).as[TransactionIndex]

  implicit val schemaForNonce: Schema[Nonce] = Schema.schemaForBigInt
    .validate(Validator.positiveOrZero)
    .map(x => Some(Nonce(x)))(_.value)
  implicit val schemaForAddress: Schema[Address] =
    schemaForByteString
      .validate(
        Validator
          .all[IndexedSeq[Byte]](Validator.minSize(1), Validator.maxSize(Address.Length))
          .contramap[ByteString](identity)
      )
      .map(x => Some(Address(x)))(_.bytes)

  implicit val schemaForGas: Schema[Gas] = bigIntFromHexString
    .validate(Validator.positiveOrZero)
    .map(Gas(_).some)(_.value)
  implicit val schemaForCallTransaction: Schema[CallTransaction] = Schema.derived
  implicit val schemaForCallResponse: Schema[CallResponse]       = schemaForByteString.map(CallResponse(_).some)(_.returnData)

  implicit val schemaForRcpAccessListItemResponse: Schema[RpcAccessListItemResponse] = Schema.derived
  implicit val schemaForRpcFullTxResponse: Schema[RpcFullTransactionResponse]        = Schema.derived
  implicit val schemaForRpcBlockResponse: Schema[RpcBlockResponse]                   = Schema.derived
  implicit val schemaForGetBlockByHashResponse: Schema[GetBlockByHashResponse]       = Schema.derived

  implicit val schemaForECDSAPublicKey: Schema[ECDSA.PublicKey] = schemaForByteString.as[ECDSA.PublicKey]
  implicit val schemaForEdDSAPublicKey: Schema[EdDSA.PublicKey] = schemaForByteString.as[EdDSA.PublicKey]
  implicit lazy val schemaForAbstractPublicKey: Schema[AbstractPublicKey] =
    schemaForByteString.as[AbstractPublicKey]
  implicit lazy val schemaForAbstractSignature: Schema[AnySignature] = schemaForByteString.as[AnySignature]

  implicit val schemaForECDSASignatureNoRecovery: Schema[SidechainSignatureNoRecovery] = Schema.derived
  implicit val schemaForEdDSASignature: Schema[EdDSA.Signature]                        = schemaForByteString.as[EdDSA.Signature]
  implicit val ECDSASignatureSchema: Schema[ECDSASignature] =
    Schema.schemaForByteArray
      .validate(Validator.fixedSize(ECDSASignature.EncodedLength).contramap(_.toSeq))
      .as[ECDSASignature]
      .validate(
        Validator.custom(
          signature => ValidationResult.validWhen(ECDSASignature.allowedPointSigns contains signature.v),
          Some(
            s"The 'v' portion of the signature (${ECDSASignature.VLength} last bytes) must be equal to one of the values: ${ECDSASignature.allowedPointSigns
              .mkString(", ")}"
          )
        )
      )

  implicit val schemaForNodeState: Schema[NodeState]                     = Schema.derived
  implicit val schemaForHealthcheckResult: Schema[HealthcheckResult]     = Schema.derived
  implicit val schemaForHealthcheckResponse: Schema[HealthcheckResponse] = Schema.derived

  implicit val schemaForChainMode: Schema[ChainMode] = Schema.schemaForString.as[ChainMode]
  implicit val schemaForFiniteDuration: Schema[FiniteDuration] =
    Schema.schemaForScalaDuration.map(duration =>
      Some(duration).collect { case finiteDuration: FiniteDuration => finiteDuration }
    )(identity)
  implicit lazy val schemaJavaDuration: Schema[Duration] = bigIntFromHexString
    .validate(Validator.positiveOrZero)
    .map(i => Duration.ofSeconds(i.toInt).some)(_.toSeconds)

  implicit val schemaForGetNetworkInfoResponse: Schema[GetNetworkInfoResponse] = Schema.derived

  // Manifest for @newtype classes - required for now, as it's required by json4s (which is used by Armadillo)
  implicit val manifestBlockNumber: Manifest[BlockNumber] = new Manifest[BlockNumber] {
    override def runtimeClass: Class[_] = BlockNumber.getClass
  }

  implicit val manifestGas: Manifest[Gas] = new Manifest[Gas] {
    override def runtimeClass: Class[_] = Gas.getClass
  }

  implicit val manifestToken: Manifest[Token] = new Manifest[Token] {
    override def runtimeClass: Class[_] = Token.getClass
  }

  implicit lazy val schemaForBlockNumber: Schema[BlockNumber] = Schema.schemaForBigInt
    .map(b => Some(BlockNumber(b)))(_.value)
    .validate(Validator.positiveOrZero)

  implicit def schemaForAlwaysNull: Schema[AlwaysNull] =
    Schema
      .schemaForOption[Unit]
      .map[AlwaysNull](_ => Some(AlwaysNull))(_ => None)
      .validate(Validator.custom(_ => ValidationResult.Valid, Some("field is always null")))

  implicit lazy val schemaRpcPooledTransaction: Schema[RpcPooledTransaction] = Schema.derived
  implicit lazy val schemaForByNonce: Schema[TxsByNonce] =
    Schema.schemaForMap[Nonce, RpcPooledTransaction](_.value.toString(16))
  implicit lazy val schemaForBySenderAndNonce: Schema[TxsBySenderAndNonce] =
    Schema.schemaForMap[Address, TxsByNonce](_.bytes.map(_.toHexString).mkString)
  implicit lazy val schemaForGetContentResponse: Schema[GetContentResponse] = Schema.derived

  implicit val schemaForTransactionLog: Schema[TransactionLog]                         = Schema.derived
  implicit val schemaForTransactionReceiptResponse: Schema[TransactionReceiptResponse] = Schema.derived

  implicit val schemaForNestableTopicSearch: Schema[NestableTopicSearch] = Schema.derived
  implicit val schemaForTopicSearch: Schema[TopicSearch]                 = Schema.derived
  implicit val schemaForGetLogsRequest: Schema[GetLogsRequest]           = Schema.derived

  implicit val schemaForTransactionLogWithRemoved: Schema[TransactionLogWithRemoved] = Schema.derived

  implicit lazy val schemaUnlockAccountRequest: Schema[UnlockAccountRequest] = Schema.derived
  implicit lazy val schemaTransactionRequest: Schema[TransactionRequest]     = Schema.derived

  implicit val schemaForSlot: Schema[Slot]                         = Schema.schemaForBigInt.map(slot => Some(Slot(slot)))(_.number)
  implicit val schemaForBlock: Schema[Block]                       = Schema.derived
  implicit val schemaForLink: Schema[Link]                         = Schema.derived
  implicit val schemaForGetChainResponse: Schema[GetChainResponse] = Schema.derived
  implicit lazy val schemaBlockOffset: Schema[BlockOffset] = bigIntFromHexString
    .validate(Validator.positiveOrZero)
    .map[BlockOffset](BlockOffset(_).some)(_.value)

  implicit lazy val schemaForMemorySlot: Schema[MemorySlot] = bigIntFromHexString
    .validate(Validator.positiveOrZero)
    .map(MemorySlot(_).some)(_.value)
  implicit lazy val schemaForTransaction: Schema[Transaction]                                = Schema.derived
  implicit lazy val schemaForSignedTransaction: Schema[SignedTransaction]                    = Schema.derived
  implicit val schemaForAccessListItem: Schema[AccessListItem]                               = Schema.derived
  implicit val schemaForTypedTransaction: Schema[TypedTransaction]                           = Schema.derived
  implicit lazy val schemaForObftBody: Schema[ObftBody]                                      = Schema.derived
  implicit lazy val schemaForObftHeader: Schema[ObftHeader]                                  = Schema.derived
  implicit lazy val schemaForObftBlock: Schema[ObftBlock]                                    = Schema.derived
  implicit val schemaForChainConsistencyError: Schema[ChainConsistencyError]                 = Schema.derived
  implicit val schemaForCheckChainConsistencyResponse: Schema[CheckChainConsistencyResponse] = Schema.derived
  implicit val schemaForCheckChainConsistencyRequest: Schema[CheckChainConsistencyRequest] = Schema.derived
    .validate(Validator.custom({
      case CheckChainConsistencyRequest(Some(from), Some(to)) if from.value >= to.value =>
        ValidationResult.Invalid("'from' should be smaller than 'to'")
      case _ =>
        ValidationResult.Valid
    }))

  implicit val schemaForTraceParameters: Schema[TraceParameters] = Schema.derived.validate(
    Validator.custom(traceParameters =>
      traceParameters.timeout match {
        case Some(value) if value < 0.second => ValidationResult.Invalid("Timeout must be a positive duration")
        case _                               => ValidationResult.Valid
      }
    )
  )

  implicit val schemaForTraceTransactionResponse: Schema[TraceTransactionResponse] =
    Schema.schemaForString.as[TraceTransactionResponse]

  implicit val schemaForClientVersion: Schema[ClientVersion]         = Schema.schemaForString.as[ClientVersion]
  implicit val schemaForNetVersion: Schema[NetVersion]               = Schema.schemaForString.as[NetVersion]
  implicit val schemaForPassphrase: Schema[Passphrase]               = Schema.schemaForString.as[Passphrase]
  implicit val schemaForSmartContractCode: Schema[SmartContractCode] = schemaForByteString.as[SmartContractCode]
  implicit val schemaForStorageData: Schema[StorageData]             = schemaForByteString.as[StorageData]
  implicit val schemaForRawPrivateKey: Schema[RawPrivateKey]         = schemaForByteString.as[RawPrivateKey]
}

object CommonRpcSchemas extends CommonRpcSchemas
