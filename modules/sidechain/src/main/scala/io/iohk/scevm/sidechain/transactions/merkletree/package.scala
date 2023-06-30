package io.iohk.scevm.sidechain.transactions

import cats.Show
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops.toCoercibleIdOps
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.showForByteString

import scala.language.implicitConversions

package object merkletree {
  @newtype final case class MerkleProof(value: List[Up])
  object MerkleProof {
    implicit val show: Show[MerkleProof] = MerkleProof.deriving[Show]
  }
  @newtype final case class RootHash(value: ByteString)
  object RootHash {
    implicit val show: Show[RootHash] = deriving

    def decodeUnsafe(hex: String): RootHash = Hex.decodeUnsafe(hex).coerce
  }

  sealed trait Side
  object Side {
    case object Left  extends Side
    case object Right extends Side

    implicit val show: Show[Side] = Show.show {
      case Left  => "Left"
      case Right => "Right"
    }
  }

  final case class Up(siblingSide: Side, sibling: RootHash)
  object Up {
    implicit val show: Show[Up] = cats.derived.semiauto.show
  }
}
