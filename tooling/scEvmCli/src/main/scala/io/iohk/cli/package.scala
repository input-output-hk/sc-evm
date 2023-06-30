package io.iohk

import cats.effect.std.Console
import cats.effect.{ExitCode, IO}
import io.circe.generic.semiauto.deriveDecoder
import io.circe.{Decoder, Encoder}
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.jsonRpcClient.JsonRpcClient
import io.iohk.scevm.domain.{Address, BlockHash, BlockNumber, Token, TransactionHash}
import io.iohk.scevm.trustlesssidechain.cardano.{MainchainBlockNumber, MainchainTxHash, UtxoId}

package object cli {

  def handleError(message: String, exception: Throwable, isVerbose: Boolean): IO[ExitCode] =
    for {
      _ <- Console[IO].error(message) // scalastyle:ignore
      _ <- if (isVerbose) {
             Console[IO].printStackTrace(exception)
           } else {
             Console[IO].errorln("For more information, re-run with verbose") // scalastyle:ignore
           }
    } yield ExitCode.Error

  final case class SidechainParamsRpc(
      chainId: BigInt,
      genesisHash: BlockHash,
      genesisCommitteeUtxo: UtxoId,
      genesisMintUtxo: Option[UtxoId],
      threshold: SidechainParamsRpc.Threshold
  )

  object SidechainParamsRpc {
    final case class Threshold(numerator: Long, denominator: Long)

    implicit val thresholdDecoder: Decoder[Threshold]                = deriveDecoder
    implicit val sidechainParamsDecoder: Decoder[SidechainParamsRpc] = deriveDecoder
  }

  def getSidechainParams[F[_]](implicit jsonRpcClient: JsonRpcClient[F]): F[SidechainParamsRpc] =
    jsonRpcClient.callEndpoint[SidechainParamsRpc]("sidechain_getParams", Nil)

  def sidechainParamsToCtlParams(sidechainParams: SidechainParamsRpc): List[String] =
    List(
      "--sidechain-genesis-hash",
      sidechainParams.genesisHash.toHex,
      "--genesis-committee-hash-utxo",
      sidechainParams.genesisCommitteeUtxo.toString,
      "--sidechain-id",
      sidechainParams.chainId.toString,
      "--threshold-numerator",
      sidechainParams.threshold.numerator.toString,
      "--threshold-denominator",
      sidechainParams.threshold.denominator.toString
    )

  implicit val byteStringDecoder: Decoder[ByteString] =
    Decoder.decodeString.emap(Hex.decode)
  implicit val bigIntDecoder: Decoder[BigInt] =
    Decoder.decodeString.map(Hex.parseHexNumberUnsafe)

  implicit val addressDecoder: Decoder[Address]                 = byteStringDecoder.emap(Address.fromBytes)
  implicit val tokenDecoder: Decoder[Token]                     = bigIntDecoder.map(Token.apply)
  implicit val mainchainTxHashDecoder: Decoder[MainchainTxHash] = Decoder.decodeString.emap(MainchainTxHash.decode)
  implicit val mainchainBlockNumberDecoder: Decoder[MainchainBlockNumber] =
    Decoder.decodeLong.map(MainchainBlockNumber.apply)
  implicit val transactionHashDecoder: Decoder[TransactionHash] = byteStringDecoder.map(TransactionHash.apply)
  implicit val blockHashDecoder: Decoder[BlockHash]             = byteStringDecoder.map(BlockHash.apply)
  implicit val blockNumberDecoder: Decoder[BlockNumber]         = bigIntDecoder.map(BlockNumber.apply)
  implicit val utxoIdDecoder: Decoder[UtxoId]                   = Decoder.decodeString.emap(UtxoId.parse)

  implicit val byteStringEncoder: Encoder[ByteString]                     = Encoder.encodeString.contramap(bs => "0x" + Hex.toHexString(bs))
  implicit val bigIntEncoder: Encoder[BigInt]                             = byteStringEncoder.contramap(bi => ByteString(bi.toByteArray))
  implicit val addressEncoder: Encoder[Address]                           = Encoder.encodeString.contramap(_.toString)
  implicit val tokenEncoder: Encoder[Token]                               = Encoder.encodeBigInt.contramap(_.value)
  implicit val mainchainTxHashEncoder: Encoder[MainchainTxHash]           = byteStringEncoder.contramap(_.value)
  implicit val mainchainBlockNumberEncoder: Encoder[MainchainBlockNumber] = Encoder.encodeLong.contramap(_.value)

}
