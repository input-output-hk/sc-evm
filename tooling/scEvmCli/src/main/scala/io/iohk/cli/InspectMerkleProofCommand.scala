package io.iohk.cli

import cats.Monad
import cats.data.EitherT
import cats.effect.std.Console
import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import com.monovore.decline.Opts
import io.bullet.borer.Cbor
import io.circe.Encoder
import io.circe.generic.semiauto
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.Token
import io.iohk.scevm.plutus.{Datum, DatumDecoder}
import io.iohk.scevm.sidechain.transactions.MerkleProofProvider.CombinedMerkleProof
import io.iohk.scevm.sidechain.transactions.MerkleProofService.{SerializedMerkleProof, combinedMerkleProofDatumCodec}
import io.iohk.scevm.sidechain.transactions.merkletree.{MerkleProof, RootHash, Side}
import io.iohk.scevm.sidechain.transactions.{
  MerkleTreeEntry,
  OutgoingTransaction,
  OutgoingTxId,
  OutgoingTxRecipient,
  merkletree
}

object InspectMerkleProofCommand {

  final private case class Params(serializedMp: SerializedMerkleProof, verbose: Boolean)

  private val merkleProofArg: Opts[SerializedMerkleProof] = Opts
    .argument[String](
      "Serialized merkle proof as hex (in the same form as obtained from sidechain_getOutgoingTxMerkleProof endpoint)"
    )
    .mapValidated(str => Hex.decode(str).toValidatedNel)
    .map(SerializedMerkleProof.apply)

  private val verbose: Opts[Boolean] = Opts.flag("verbose", "Display additional information").orFalse

  private val inspectMerkleProofOpt: Opts[Params] =
    Opts.subcommand("inspect-proof", "Inspect content of the serialized merkle proof")(
      (merkleProofArg, verbose).mapN(Params.apply)
    )

  final case class Output(
      merkleProof: MerkleProof,
      previousMerkleRoot: Option[RootHash],
      transaction: OutgoingTransaction
  )

  def apply(): Opts[IO[ExitCode]] =
    inspectMerkleProofOpt.map { case Params(serializedMp, verbose) =>
      (for {
        _          <- logIfVerbose("Decoding merkle proof...", verbose)
        combinedMp <- decodeCborDatum[CombinedMerkleProof](serializedMp.bytes)
        _          <- logIfVerbose(show"Decoded merkle proof: $combinedMp", verbose)
      } yield encodeOutput(combinedMp)).value.flatMap {
        case Left(err)         => Console[IO].errorln(s"Error: $err").as(ExitCode.Error)
        case Right(jsonOutput) => Console[IO].println(jsonOutput).as(ExitCode.Success) // scalastyle:ignore
      }
    }

  private def logIfVerbose(message: String, verbose: Boolean) =
    EitherT.liftF[IO, String, Unit](
      Monad[IO].whenA(verbose)(Console[IO].errorln(message))
    )

  private def encodeOutput(combinedMp: CombinedMerkleProof) = {
    implicit val byteStringEncoder: Encoder[ByteString] =
      Encoder[String].contramap[ByteString](Hex.toHexString)
    implicit val rootHashEncoder: Encoder[RootHash] = merkletree.RootHash.deriving
    implicit val sideEncoder: Encoder[Side] = Encoder[String].contramap {
      case Side.Left  => "Left"
      case Side.Right => "Right"
    }
    implicit val upEncoder: Encoder[merkletree.Up]        = semiauto.deriveEncoder
    implicit val merkleProofEncoder: Encoder[MerkleProof] = MerkleProof.deriving
    implicit val tokenEncoder: Encoder[Token]             = Token.deriving
    implicit val outgoingTransactionRecipientEncoder: Encoder[OutgoingTxRecipient] =
      OutgoingTxRecipient.deriving
    implicit val outgoingTxIdEncoder: Encoder[OutgoingTxId]               = OutgoingTxId.deriving
    implicit val outgoingTransactionEncoder: Encoder[OutgoingTransaction] = semiauto.deriveEncoder
    implicit val outputEncoder: Encoder[Output]                           = semiauto.deriveEncoder

    val merkleTreeEntry = combinedMp.transaction
    Encoder[Output].apply(
      Output(
        combinedMp.merkleProof,
        merkleTreeEntry.previousOutgoingTransactionsBatch,
        toOutgoingTx(merkleTreeEntry)
      )
    )
  }

  private def toOutgoingTx(merkleTreeEntry: MerkleTreeEntry) =
    OutgoingTransaction(merkleTreeEntry.amount, merkleTreeEntry.recipient, merkleTreeEntry.index)

  private def decodeCborDatum[T: DatumDecoder](bytes: ByteString) =
    EitherT(IO.fromTry(Cbor.decode(bytes.toArray).to[Datum].valueTry).attempt)
      .leftMap(_.getMessage)
      .subflatMap(DatumDecoder[T].decode(_).left.map(_.message))
}
