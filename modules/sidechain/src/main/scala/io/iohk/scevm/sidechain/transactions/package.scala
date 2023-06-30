package io.iohk.scevm.sidechain

import cats.Show
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops.toCoercibleIdOps
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain._
import io.iohk.scevm.plutus.DatumCodec
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash

import scala.language.implicitConversions

package object transactions {

  /** @param value should contain bech32 decoded cardano address but there is no validation at this field atm.
    */
  @newtype final case class OutgoingTxRecipient(value: ByteString)
  object OutgoingTxRecipient {
    implicit val show: Show[OutgoingTxRecipient] = deriving

    def decode(str: String): Either[String, OutgoingTxRecipient] =
      Hex.decode(str).map(_.coerce)

    def decodeUnsafe(str: String): OutgoingTxRecipient =
      Hex.decodeUnsafe(str).coerce
  }
  @newtype final case class OutgoingTxId(value: Long)
  object OutgoingTxId {
    // Manifest for @newtype classes - required for now, as it's required by json4s (which is used by Armadillo)
    implicit val manifestOutgoingTxId: Manifest[OutgoingTxId] = new Manifest[OutgoingTxId] {
      override def runtimeClass: Class[_] = OutgoingTxId.getClass
    }

    implicit val show: Show[OutgoingTxId] = OutgoingTxId.deriving
  }
  final case class MerkleTreeEntry(
      index: OutgoingTxId,
      amount: Token,
      recipient: OutgoingTxRecipient,
      previousOutgoingTransactionsBatch: Option[RootHash]
  )
  implicit val outgoingTxIdEncoder: DatumCodec[OutgoingTxId]                 = OutgoingTxId.deriving[DatumCodec]
  implicit val rootHashDatumEncoder: DatumCodec[RootHash]                    = RootHash.deriving
  implicit val tokenDatumEncoder: DatumCodec[Token]                          = Token.deriving
  implicit val mainchainAddressDatumEncoder: DatumCodec[OutgoingTxRecipient] = OutgoingTxRecipient.deriving
  implicit val merkleTreeEntry: DatumCodec[MerkleTreeEntry]                  = DatumCodec.derive

}
