package io.iohk.scevm.sidechain.transactions.merkletree

import cats.data.NonEmptyList
import io.iohk.bytes.ByteString
import io.iohk.scevm.trustlesssidechain.cardano.Blake2bHash32

import scala.annotation.tailrec

/** Following implementation was done based on https://github.com/mlabs-haskell/trustless-sidechain/blob/master/src/TrustlessSidechain/MerkleTree.hs
  * These two implementations have to be in sync and output exactly the same hashes for the same inputs.
  * Below code is not necessarily an idiomatic scala but the most important property of it was to closely follow haskell's implementation
  */
sealed trait MerkleTree {
  def rootHash: RootHash
}
object MerkleTree {
  final case class Tip(rootHash: RootHash)                                      extends MerkleTree
  final case class Bin(rootHash: RootHash, left: MerkleTree, right: MerkleTree) extends MerkleTree

  def height(tree: MerkleTree): Int =
    tree match {
      case Tip(_)              => 1
      case Bin(_, left, right) => 1 + Math.max(height(left), height(right))
    }

  private def hashInternalNode(data: ByteString): RootHash = hash(ByteString(1.toByte).concat(data))

  private def hashLeafNode(data: ByteString): RootHash = hash(ByteString(0.toByte).concat(data))

  private def hash(data: ByteString): RootHash = RootHash(Blake2bHash32.hash(data).bytes)

  private def mergeRootHashes(left: RootHash, right: RootHash): RootHash = hashInternalNode(
    left.value.concat(right.value)
  )

  def fromNonEmptyList(nel: NonEmptyList[ByteString]): MerkleTree = {
    @tailrec
    def mergeAll(ms: List[MerkleTree]): MerkleTree =
      if (ms.size == 1) {
        ms.head
      } else {
        mergeAll(mergePairs(ms, Nil))
      }

    @tailrec
    def mergePairs(pairs: List[MerkleTree], acc: List[MerkleTree]): List[MerkleTree] =
      pairs match {
        case a :: b :: cs =>
          mergePairs(cs, Bin(mergeRootHashes(a.rootHash, b.rootHash), a, b) :: acc)
        case a :: Nil => a :: acc
        case Nil      => acc
      }

    mergeAll(nel.map(bs => Tip(hashLeafNode(bs))).toList)
  }

  def lookupMp(value: ByteString, root: MerkleTree): Option[MerkleProof] = {
    val hash = hashLeafNode(value)
    def go(merkleTree: MerkleTree, proof: List[Up]): Option[List[Up]] =
      merkleTree match {
        case Tip(rootHash) =>
          if (hash == rootHash) Some(proof)
          else None
        case Bin(_, left, right) =>
          val lHash = left.rootHash
          val rHash = right.rootHash
          go(right, Up(Side.Left, lHash) :: proof)
            .orElse(go(left, Up(Side.Right, rHash) :: proof))
      }
    go(root, Nil).map(MerkleProof.apply)
  }

  def memberMp(value: ByteString, proof: MerkleProof, rootHash: RootHash): Boolean = {
    @tailrec
    def go(acc: RootHash, ps: List[Up]): RootHash =
      ps match {
        case Nil => acc
        case ::(p, ps) =>
          p.siblingSide match {
            case Side.Left  => go(mergeRootHashes(p.sibling, acc), ps)
            case Side.Right => go(mergeRootHashes(acc, p.sibling), ps)
          }
      }
    rootHash == go(hashLeafNode(value), proof.value)
  }
}
