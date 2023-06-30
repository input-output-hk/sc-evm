package io.iohk.scevm.rpc.serialization

import cats.syntax.all._
import io.iohk.armadillo.json.json4s.Json4sSupport.JsonRpcIdSerializer
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignature, ECDSASignatureNoRecovery, EdDSA}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain._
import io.iohk.scevm.rpc.AlwaysNull
import io.iohk.scevm.rpc.controllers.DebugController.{TraceParameters, TraceTransactionResponse}
import io.iohk.scevm.rpc.controllers.EthTransactionController._
import io.iohk.scevm.rpc.controllers.EthVMController.{CallResponse, CallTransaction}
import io.iohk.scevm.rpc.controllers.EthWorldStateController.{SmartContractCode, StorageData}
import io.iohk.scevm.rpc.controllers.NetController.{ChainMode, NetVersion}
import io.iohk.scevm.rpc.controllers.PersonalController.{Passphrase, RawPrivateKey}
import io.iohk.scevm.rpc.controllers.SanityController.CheckChainConsistencyRequest
import io.iohk.scevm.rpc.controllers.TxPoolController.RpcPooledTransaction
import io.iohk.scevm.rpc.controllers.Web3Controller.ClientVersion
import io.iohk.scevm.rpc.controllers.{BlockParam, EthBlockParam, EthTransactionController, ExtendedBlockParam}
import io.iohk.scevm.rpc.domain.JsonRpcError.JsonRpcErrorThrowable
import io.iohk.scevm.rpc.domain.{JsonRpcError, RpcFullTransactionResponse, TransactionRequest}
import io.iohk.scevm.utils.NodeState
import org.json4s.JsonAST._
import org.json4s.{CustomKeySerializer, CustomSerializer, DefaultFormats, Extraction, FieldSerializer, Formats}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Try

// scalastyle:off number.of.types
// scalastyle:off number.of.methods
object JsonSerializers {

  private def preserveNull(field: String): PartialFunction[(String, Any), Option[(String, Any)]] = {
    case (`field`, None)  => Some((field, null))
    case (`field`, value) => Some((field, value))
  }

  implicit lazy val formats: Formats =
    DefaultFormats +
      ByteStringKeySerializer +
      ByteStringSerializer +
      QuantitiesSerializer +
      OptionNoneToJNullSerializer +
      AddressJsonSerializer +
      ByteSerializer +
      BlockHashSerializer +
      BlockNumberSerializer +
      EthBlockParamSerializer +
      ExtendedBlockParamSerializer +
      EcdsaPubKeySerializer +
      ECDSASignatureNoRecoverySerializer +
      ECDSASignatureSerializer +
      EdDSAPublicKeySerializer +
      EdDSASignatureSerializer +
      RpcFullTransactionResponseSerializer +
      EpochPhaseSerializer +
      JsonRpcIdSerializer +
      ChainModeSerializer +
      FiniteDurationSerializer +
      NodeStateSerializer +
      CallTransactionSerializer +
      BlockParamSerializer +
      CallResponseSerializer +
      TransactionHashSerializer +
      AddressKeySerializer +
      NonceKeySerializer +
      NonceSerializer +
      RpcPooledTransactionSerializer +
      AlwaysNullSerializer +
      NonceSerializer +
      TopicSearchSerializer +
      GetLogsRequestSerializer +
      DurationSerializer +
      TransactionRequestSerializer +
      BlockOffsetSerializer +
      MemorySlotSerializer +
      CheckChainConsistencyRequestSerializer +
      TransactionIndexSerializer +
      TraceParametersSerializer +
      TraceTransactionResponseSerializer +
      ClientVersionResponseSerializer +
      NetVersionSerializer +
      PassphraseSerializer +
      SmartContractCodeSerializer +
      StorageDataCodeSerializer +
      RawPrivateKeySerializer +
      GasSerializer +
      TokenSerializer +
      TokenKeySerializer

  val RpcFullTransactionResponseSerializer: FieldSerializer[RpcFullTransactionResponse] =
    FieldSerializer[RpcFullTransactionResponse](serializer = preserveNull("to"))

  object ByteStringKeySerializer
      extends CustomKeySerializer[ByteString](_ =>
        (
          PartialFunction.empty,
          { case bs: ByteString =>
            JsonMethodsImplicits.encodeAsHex(bs).s
          }
        )
      )

  object ByteStringSerializer
      extends CustomSerializer[ByteString](_ =>
        (
          Function
            .unlift((js: JString) => JsonMethodsImplicits.extractBytes(js.s).toOption)
            .compose[JValue] { case js: JString => js },
          { case bs: ByteString => JsonMethodsImplicits.encodeAsHex(bs) }
        )
      )

  object QuantitiesSerializer
      extends CustomSerializer[BigInt](_ =>
        (
          { case JString(s) =>
            Hex.parseHexNumberUnsafe(s)
          },
          { case n: BigInt =>
            if (n == 0)
              JString("0x0")
            else
              JString(s"0x${Hex.toHexString(n.toByteArray).dropWhile(_ == '0')}")
          }
        )
      )

  object ByteSerializer
      extends CustomSerializer[Byte](_ =>
        (
          PartialFunction.empty,
          { case b: Byte => JsonMethodsImplicits.encodeAsHex(b) }
        )
      )

  object OptionNoneToJNullSerializer
      extends CustomSerializer[Option[_]](_ =>
        (
          PartialFunction.empty,
          { case None =>
            JNull
          }
        )
      )

  object AddressJsonSerializer
      extends CustomSerializer[Address](_ =>
        (
          Function
            .unlift((js: JString) => Try(Address(js.s)).toOption)
            .compose[JValue] { case js: JString => js },
          { case addr: Address => JsonMethodsImplicits.encodeAsHex(addr.bytes) }
        )
      )

  object RpcErrorJsonSerializer
      extends CustomSerializer[JsonRpcError](_ =>
        (
          PartialFunction.empty,
          { case err: JsonRpcError => JsonEncoder.encode(err) }
        )
      )

  object BlockHashSerializer
      extends CustomSerializer[BlockHash](_ =>
        (
          { case JString(s) => BlockHash(ByteString(JsonMethodsImplicits.decode(s))) },
          { case bs: BlockHash => JsonMethodsImplicits.encodeAsHex(bs.byteString) }
        )
      )

  object BlockNumberSerializer
      extends CustomSerializer[BlockNumber](_ =>
        (
          { case JInt(i) => BlockNumber(i) },
          PartialFunction.empty
        )
      )

  object TransactionHashSerializer
      extends CustomSerializer[TransactionHash](_ =>
        (
          { case JString(s) => TransactionHash(ByteString(JsonMethodsImplicits.decode(s))) },
          { case TransactionHash(byteString) => JsonMethodsImplicits.encodeAsHex(byteString) }
        )
      )

  object TransactionIndexSerializer
      extends CustomSerializer[TransactionIndex](_ =>
        (
          { case JInt(i) => TransactionIndex(i) },
          PartialFunction.empty
        )
      )

  object EthBlockParamSerializer
      extends CustomSerializer[EthBlockParam](_ =>
        (
          { case JString(s) =>
            Try(BigInt(1, JsonMethodsImplicits.decode(s)))
              .map(n => BlockParam.ByNumber(BlockNumber(n)))
              .getOrElse {
                s match {
                  case "latest"   => BlockParam.Latest
                  case "pending"  => BlockParam.Pending
                  case "earliest" => BlockParam.Earliest
                }
              }
          },
          { case bp: EthBlockParam =>
            bp match {
              case BlockParam.ByNumber(n) => JsonMethodsImplicits.encodeAsHex(n.value)
              case BlockParam.Latest      => JString("latest")
              case BlockParam.Pending     => JString("pending")
              case BlockParam.Earliest    => JString("earliest")
            }
          }
        )
      )

  object ExtendedBlockParamSerializer
      extends CustomSerializer[ExtendedBlockParam](_ =>
        (
          { case JString(s) =>
            Try(BigInt(1, JsonMethodsImplicits.decode(s)))
              .map(n => BlockParam.ByNumber(BlockNumber(n)))
              .getOrElse {
                s match {
                  case "latest"   => BlockParam.Latest
                  case "pending"  => BlockParam.Pending
                  case "earliest" => BlockParam.Earliest
                  case "stable"   => BlockParam.Stable
                }
              }
          },
          { case bp: ExtendedBlockParam =>
            bp match {
              case BlockParam.ByNumber(n) => JsonMethodsImplicits.encodeAsHex(n.value)
              case BlockParam.Latest      => JString("latest")
              case BlockParam.Pending     => JString("pending")
              case BlockParam.Earliest    => JString("earliest")
              case BlockParam.Stable      => JString("stable")
            }
          }
        )
      )

  object BlockParamSerializer
      extends CustomSerializer[BlockParam](formats =>
        (
          ExtendedBlockParamSerializer
            .deserializePartial(formats)
            .orElse(EthBlockParamSerializer.deserializePartial(formats)),
          ExtendedBlockParamSerializer.serialize(formats).orElse(EthBlockParamSerializer.serialize(formats))
        )
      )

  object EcdsaPubKeySerializer
      extends CustomSerializer[ECDSA.PublicKey](_ =>
        (
          { case JString(v) => ECDSA.PublicKey.fromHexUnsafe(v) },
          { case bs: ECDSA.PublicKey => JsonMethodsImplicits.encodeAsHex(bs.bytes) }
        )
      )

  object ECDSASignatureNoRecoverySerializer
      extends CustomSerializer[ECDSASignatureNoRecovery](_ =>
        (
          { case JString(v) => ECDSASignatureNoRecovery.fromHexUnsafe(v) },
          { case signature: ECDSASignatureNoRecovery => JsonMethodsImplicits.encodeAsHex(signature.toBytes) }
        )
      )

  object ECDSASignatureSerializer
      extends CustomSerializer[ECDSASignature](_ =>
        (
          { case JString(v) => ECDSASignature.fromHexUnsafe(v) },
          { case signature: ECDSASignature => JsonMethodsImplicits.encodeAsHex(signature.toBytes) }
        )
      )

  object EdDSAPublicKeySerializer
      extends CustomSerializer[EdDSA.PublicKey](_ =>
        (
          { case JString(v) => EdDSA.PublicKey.fromHexUnsafe(v) },
          { case pubKey: EdDSA.PublicKey => JsonMethodsImplicits.encodeAsHex(pubKey.bytes) }
        )
      )

  object EdDSASignatureSerializer
      extends CustomSerializer[EdDSA.Signature](_ =>
        (
          { case JString(v) => EdDSA.Signature.fromHexUnsafe(v) },
          { case signature: EdDSA.Signature => JsonMethodsImplicits.encodeAsHex(signature.bytes) }
        )
      )

  object EpochPhaseSerializer
      extends CustomSerializer[EpochPhase](_ =>
        (
          EpochPhase.fromRaw.compose { case JString(str) => str },
          { case phase: EpochPhase =>
            JString(phase.raw)
          }
        )
      )

  object NodeStateSerializer
      extends CustomSerializer[NodeState](_ =>
        (
          {
            case JString("Starting")                 => NodeState.Starting
            case JString("Syncing")                  => NodeState.Syncing
            case JString("Running")                  => NodeState.Running
            case JString("TransitioningFromSyncing") => NodeState.TransitioningFromSyncing
          },
          { case nodeState: NodeState =>
            nodeState match {
              case NodeState.Starting                 => JString("Starting")
              case NodeState.Syncing                  => JString("Syncing")
              case NodeState.Running                  => JString("Running")
              case NodeState.TransitioningFromSyncing => JString("TransitioningFromSyncing")
            }
          }
        )
      )

  object ChainModeSerializer
      extends CustomSerializer[ChainMode](_ =>
        (
          {
            case JString("Standalone") => ChainMode.Standalone
            case JString("Sidechain")  => ChainMode.Sidechain
          },
          { case chainMode: ChainMode =>
            chainMode match {
              case ChainMode.Standalone => JString("Standalone")
              case ChainMode.Sidechain  => JString("Sidechain")
            }
          }
        )
      )

  object FiniteDurationSerializer
      extends CustomSerializer[FiniteDuration](_ =>
        (
          {
            case JString(value) if Duration(value).isFinite => Duration(value).asInstanceOf[FiniteDuration]
          },
          { case finiteDuration: FiniteDuration =>
            JString(finiteDuration.toString())
          }
        )
      )

  object CallTransactionSerializer
      extends CustomSerializer[CallTransaction](_ =>
        (
          Function
            .unlift((obj: JObject) => EthVMJsonImplicits.extractCall(obj).toOption)
            .compose[JValue] { case obj: JObject => obj },
          { case c: CallTransaction =>
            val optionalFields: List[(String, JValue)] = List(
              c.from.map(from => "from" -> JsonMethodsImplicits.encodeAsHex(from)),
              c.to.map(to => "to" -> JsonMethodsImplicits.encodeAsHex(to.bytes)),
              c.gas.map(gasLimit => "gasLimit" -> JsonMethodsImplicits.encodeAsHex(gasLimit.value)),
              c.gasPrice.map(gasPrice => "gasPrice" -> JsonMethodsImplicits.encodeAsHex(gasPrice.value))
            ).flatten
            JObject(
              optionalFields :+
                ("value" -> JsonMethodsImplicits.encodeAsHex(c.value.value)) :+
                ("input" -> JsonMethodsImplicits.encodeAsHex(c.input))
            )
          }
        )
      )

  object CallResponseSerializer
      extends CustomSerializer[CallResponse](_ =>
        (
          PartialFunction.empty,
          { case resp: CallResponse =>
            JsonMethodsImplicits.encodeAsHex(resp.returnData)
          }
        )
      )

  object AddressKeySerializer
      extends CustomKeySerializer[Address](implicit formats =>
        (
          PartialFunction.empty,
          { case address: Address =>
            Extraction.decompose(address.bytes).extract[String]
          }
        )
      )

  object NonceKeySerializer
      extends CustomKeySerializer[Nonce](_ =>
        (
          PartialFunction.empty,
          { case nonce: Nonce =>
            nonce.value.toString
          }
        )
      )

  val RpcPooledTransactionSerializer: FieldSerializer[RpcPooledTransaction] =
    FieldSerializer[RpcPooledTransaction](serializer = preserveNull("to"))

  object AlwaysNullSerializer
      extends CustomSerializer[AlwaysNull](_ =>
        (
          PartialFunction.empty,
          { case AlwaysNull =>
            JNull
          }
        )
      )

  object NonceSerializer
      extends CustomSerializer[Nonce](_ =>
        (
          PartialFunction.empty,
          { case Nonce(value) => JsonMethodsImplicits.encodeAsHex(value) }
        )
      )

  object TopicSearchSerializer
      extends CustomSerializer[TopicSearch](_ =>
        (
          {
            case JArray(arr: List[JValue]) =>
              val exactTopicSearches = arr.traverse {
                case JString(s) => JsonMethodsImplicits.extractBytes(s)
                case _ =>
                  throw JsonRpcErrorThrowable(
                    JsonRpcError.InvalidParams("Topic searches should be a list of bytestring")
                  )

              } match {
                case Left(jsonRpcError) => throw JsonRpcErrorThrowable(jsonRpcError)
                case Right(byteStrings) => byteStrings.map(ExactTopicSearch)
              }
              OrTopicSearch(exactTopicSearches.toSet)

            case JString(s) =>
              val byteString = JsonMethodsImplicits.extractBytes(s) match {
                case Left(jsonRpcError) => throw JsonRpcErrorThrowable(jsonRpcError)
                case Right(value)       => value
              }
              ExactTopicSearch(byteString)

            case JNull => AnythingTopicSearch
          },
          {
            case EthTransactionController.ExactTopicSearch(byteString) => JsonMethodsImplicits.encodeAsHex(byteString)
            case EthTransactionController.AnythingTopicSearch          => JNull
            case EthTransactionController.OrTopicSearch(values) =>
              JArray(
                values.toList.map { case ExactTopicSearch(byteString) => JsonMethodsImplicits.encodeAsHex(byteString) }
              )
          }
        )
      )

  object GetLogsRequestSerializer
      extends CustomSerializer[GetLogsRequest](_ =>
        (
          Function
            .unlift((json: JObject) => EthTransactionJsonMethodsImplicits.extractGetLogsRequestByBlockHash(json))
            .orElse(
              Function.unlift((json: JObject) => EthTransactionJsonMethodsImplicits.extractGetLogsRequestByRange(json))
            )
            .compose[JValue] { case obj: JObject => obj },
          {
            case GetLogsRequestByBlockHash(blockHash, address, topics) =>
              JObject(
                "blockhash" -> BlockHashSerializer.serialize.apply(blockHash),
                "address"   -> JArray(address.map(address => AddressJsonSerializer.serialize.apply(address)).toList),
                "topics"    -> JArray(topics.map(topic => TopicSearchSerializer.serialize.apply(topic)).toList)
              )
            case GetLogsRequestByRange(fromBlock, toBlock, address, topics) =>
              JObject(
                "fromBlock" -> BlockParamSerializer.serialize.apply(fromBlock),
                "toBlock"   -> BlockParamSerializer.serialize.apply(toBlock),
                "address"   -> JArray(address.map(address => AddressJsonSerializer.serialize.apply(address)).toList),
                "topics"    -> JArray(topics.map(topic => TopicSearchSerializer.serialize.apply(topic)).toList)
              )
          }
        )
      )

  object DurationSerializer
      extends CustomSerializer[java.time.Duration](_ =>
        (
          Function.unlift(JsonMethodsImplicits.extractDurationQuantity(_).toOption),
          { case d: java.time.Duration => JsonMethodsImplicits.encodeAsHex(d.getSeconds) }
        )
      )

  object TokenKeySerializer
      extends CustomKeySerializer[Token](_ =>
        (
          PartialFunction.empty,
          { case token: Token => JsonMethodsImplicits.convertToHex(token.value) }
        )
      )

  object TransactionRequestSerializer
      extends CustomSerializer[TransactionRequest](_ =>
        (
          { case obj: JObject =>
            TransactionRequest(
              from = (obj \ "from").extract[Address],
              to = (obj \ "to").extractOpt[Address],
              value = JsonMethodsImplicits.extractQuantity(obj \ "value").map(Token.apply).toOption,
              gas = (obj \ "gas").extractOpt[BigInt].map(Gas.apply),
              gasPrice = (obj \ "gasPrice").extractOpt[BigInt].map(Token.apply),
              nonce = (obj \ "nonce").extractOpt[BigInt].map(Nonce.apply),
              data = (obj \ "data").extractOpt[ByteString]
            )
          },
          { case t: TransactionRequest =>
            val optionalFields: List[(String, JValue)] = List(
              t.value.map(from => "value" -> JsonMethodsImplicits.encodeAsHex(from.value)),
              t.to.map(to => "to" -> JsonMethodsImplicits.encodeAsHex(to.bytes)),
              t.nonce.map(nonce => "nonce" -> JsonMethodsImplicits.encodeAsHex(nonce.value)),
              t.data.map(data => "data" -> JsonMethodsImplicits.encodeAsHex(data)),
              t.gas.map(gas => "gas" -> JsonMethodsImplicits.encodeAsHex(gas.value)),
              t.gasPrice.map(gasPrice => "gasPrice" -> JsonMethodsImplicits.encodeAsHex(gasPrice.value))
            ).flatten
            JObject(optionalFields :+ ("from" -> JsonMethodsImplicits.encodeAsHex(t.from.bytes)))
          }
        )
      )

  object BlockOffsetSerializer
      extends CustomSerializer[BlockOffset](_ =>
        (
          { case json => BlockOffset(json.extract[BigInt]) },
          { case blockOffset: BlockOffset => JsonMethodsImplicits.encodeAsHex(blockOffset.value) }
        )
      )

  object MemorySlotSerializer
      extends CustomSerializer[MemorySlot](_ =>
        (
          { case json => MemorySlot(json.extract[BigInt]) },
          PartialFunction.empty
        )
      )

  object CheckChainConsistencyRequestSerializer
      extends CustomSerializer[CheckChainConsistencyRequest](_ =>
        (
          { case json: JObject =>
            SanityJsonMethodsImplicits.extractCall(json) match {
              case Left(jsonRpcError) => throw JsonRpcErrorThrowable(jsonRpcError)
              case Right(value)       => value
            }
          },
          { case c: CheckChainConsistencyRequest =>
            JObject(
              List(
                c.from.map(from => "from" -> JsonMethodsImplicits.encodeAsHex(from.value)),
                c.to.map(to => "to" -> JsonMethodsImplicits.encodeAsHex(to.value))
              ).flatten
            )
          }
        )
      )

  object TraceParametersSerializer
      extends CustomSerializer[TraceParameters](_ =>
        (
          { case obj: JObject =>
            DebugJsonMethodsImplicits.extractTraceParameters(obj).fold(e => throw JsonRpcErrorThrowable(e), identity)
          },
          PartialFunction.empty
        )
      )

  object TraceTransactionResponseSerializer
      extends CustomSerializer[TraceTransactionResponse](_ =>
        (
          { case js: JString => TraceTransactionResponse(js.s) },
          { case t: TraceTransactionResponse => JString(t.value) }
        )
      )

  object ClientVersionResponseSerializer
      extends CustomSerializer[ClientVersion](_ =>
        (
          { case js: JString => ClientVersion(js.s) },
          { case c: ClientVersion => JString(c.value) }
        )
      )

  object NetVersionSerializer
      extends CustomSerializer[NetVersion](_ =>
        (
          { case js: JString => NetVersion(js.s) },
          { case n: NetVersion => JString(n.value) }
        )
      )

  object PassphraseSerializer
      extends CustomSerializer[Passphrase](_ =>
        (
          { case js: JString => Passphrase(js.s) },
          { case _: Passphrase => JString("***") }
        )
      )

  object SmartContractCodeSerializer
      extends CustomSerializer[SmartContractCode](_ =>
        (
          { case js: JString => SmartContractCode(Hex.decodeUnsafe(js.s)) },
          { case s: SmartContractCode => JsonMethodsImplicits.encodeAsHex(s.value) }
        )
      )

  object StorageDataCodeSerializer
      extends CustomSerializer[StorageData](_ =>
        (
          { case js: JString => StorageData(Hex.decodeUnsafe(js.s)) },
          { case s: StorageData => JsonMethodsImplicits.encodeAsHex(s.value) }
        )
      )

  object RawPrivateKeySerializer
      extends CustomSerializer[RawPrivateKey](_ =>
        (
          { case js: JString => RawPrivateKey(Hex.decodeUnsafe(js.s)) },
          { case _: RawPrivateKey => JString("***") }
        )
      )

  object GasSerializer
      extends CustomSerializer[Gas](_ =>
        (
          { case js: JString => Gas(Hex.parseHexNumberUnsafe(js.s)) },
          { case gas: Gas => JsonMethodsImplicits.encodeAsHex(gas.value) }
        )
      )

  object TokenSerializer
      extends CustomSerializer[Token](_ =>
        (
          { case js: JString => Token(Hex.parseHexNumberUnsafe(js.s)) },
          { case token: Token => JsonMethodsImplicits.encodeAsHex(token.value) }
        )
      )
}
// scalastyle:on number.of.methods
// scalastyle:on number.of.types
