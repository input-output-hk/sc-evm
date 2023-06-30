package io.iohk.scevm.mpt

import io.iohk.bytes.ByteString
import io.iohk.scevm.mpt.MerklePatriciaTrie.MissingRootNodeException
import io.iohk.scevm.serialization.{ByteArraySerializable, Implicits}
import io.iohk.scevm.utils.{SimpleMap, SimpleMapImpl}

trait MptStorage extends SimpleMap[Node.Hash, Node.Encoded, MptStorage] {
  def getNodeOrFail(nodeId: Array[Byte]): MptNode =
    get(ByteString(nodeId))
      .map(nodeEncoded => MptStorage.decodeNode(nodeEncoded, nodeId))
      .getOrElse(throw new MissingRootNodeException(ByteString(nodeId)))

  def updateNodesInStorage(newRoot: Option[MptNode], toRemove: Seq[MptNode]): Option[MptNode] = {
    val (collapsed, toUpdate) = MptStorage.collapseNode(newRoot)
    val toBeRemoved           = toRemove.map(n => ByteString(n.hash))
    update(toBeRemoved, toUpdate)
    collapsed
  }
}

class EphemeralMptStorage extends MptStorage {
  private var storage = SimpleMapImpl.empty[Node.Hash, Node.Encoded]

  override def get(nodeId: Node.Hash): Option[Node.Encoded] =
    synchronized {
      storage.get(nodeId)
    }

  override def update(toRemove: Seq[Node.Hash], toUpsert: Seq[(Node.Hash, Node.Encoded)]): EphemeralMptStorage =
    synchronized {
      storage = storage.update(toRemove, toUpsert)
      this
    }
}

object MptStorage {
  def collapseNode(node: Option[MptNode]): (Option[MptNode], List[(ByteString, Array[Byte])]) =
    if (node.isEmpty)
      (None, List.empty[(ByteString, Array[Byte])])
    else {
      val (hashNode, newNodes) = MptTraversals.collapseTrie(node.get)
      (Some(hashNode), newNodes)
    }

  def decodeNode(nodeEncoded: Node.Encoded, nodeId: Array[Byte]): MptNode =
    MptTraversals.decodeNode(nodeEncoded).withCachedHash(nodeId).withCachedRlpEncoded(nodeEncoded)

  def rootHash[K](elements: Seq[K], vSerializable: ByteArraySerializable[K]): ByteString = {
    val trie = MerklePatriciaTrie[Int, K](new EphemeralMptStorage)(Implicits.intByteArraySerializable, vSerializable)
    ByteString(
      elements.zipWithIndex
        .foldLeft(trie) { case (trie, (value, index)) =>
          trie.put(index, value)
        }
        .getRootHash
    )
  }
}
