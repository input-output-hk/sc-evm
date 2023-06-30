package io.iohk.scevm.mpt

import cats.syntax.either._
import io.iohk.bytes.ByteString
import io.iohk.scevm.mpt.ProofVerifyResult.{InvalidProof, ValidProof}
import io.iohk.scevm.mpt.{EphemeralMptStorage, MerklePatriciaTrie, MptNode, MptProofError, MptStorage}
import io.iohk.scevm.serialization.{ByteArrayEncoder, ByteArraySerializable}

sealed trait ProofVerifyResult
object ProofVerifyResult {
  case object ValidProof                               extends ProofVerifyResult
  final case class InvalidProof(reason: MptProofError) extends ProofVerifyResult
}

object MptProofVerifier {

  def verifyProof[K, V](
      rootHash: Array[Byte],
      key: K,
      proof: Vector[MptNode]
  )(implicit kSer: ByteArrayEncoder[K], vSer: ByteArraySerializable[V]): ProofVerifyResult = {
    val mptStore = mkStorage(proof)
    rebuildMpt(rootHash, mptStore)(kSer, vSer)
      .flatMap(trie => getKey(key, trie))
      .fold(InvalidProof.apply, _ => ValidProof)
  }

  private def mkStorage[V, K](proof: Vector[MptNode]): MptStorage =
    new EphemeralMptStorage().update(Nil, proof.map(node => (ByteString(node.hash), node.encode)))

  private def rebuildMpt[V, K](rootHash: Array[Byte], storage: MptStorage)(implicit
      kSer: ByteArrayEncoder[K],
      vSer: ByteArraySerializable[V]
  ): Either[MptProofError, MerklePatriciaTrie[K, V]] =
    Either
      .catchNonFatal {
        MerklePatriciaTrie[K, V](
          rootHash = rootHash,
          source = storage
        )
      }
      .leftMap(_ => MptProofError.UnableRebuildMpt)

  private def getKey[V, K](key: K, trie: MerklePatriciaTrie[K, V]): Either[MptProofError, Option[V]] =
    Either
      .catchNonFatal(trie.get(key))
      .leftMap(_ => MptProofError.KeyNotFoundInRebuidMpt)
}
