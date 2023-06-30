package io.iohk.scevm.db.storage

import io.iohk.scevm.db.storage.StateStorage.FlushSituation
import io.iohk.scevm.domain.{Account, Address, ObftHeader}
import io.iohk.scevm.mpt.MptNodeEncoders._
import io.iohk.scevm.mpt.{ArchiveMptStorage, MerklePatriciaTrie, MptNode, MptStorage, Node, ReadOnlyMptStorage}

// scalastyle:off
trait StateStorage {
  def archivingStorage: MptStorage
  def readOnlyStorage: MptStorage

  def onBlockSave(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit
  def onBlockRollback(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit

  def saveNode(nodeHash: Node.Hash, nodeEncoded: Node.Encoded): Unit
  def getNode(nodeHash: Node.Hash): Option[MptNode]
  def forcePersist(reason: FlushSituation): Boolean
}

object StateStorage {
  def apply(nodeStorage: NodeStorage): StateStorage = new ArchiveStateStorage(nodeStorage)

  sealed abstract class FlushSituation
  case object GenesisDataLoad extends FlushSituation

  def mpt(stateStorage: StateStorage, header: ObftHeader): MerklePatriciaTrie[Address, Account] = {
    val storage = stateStorage.archivingStorage
    MerklePatriciaTrie(rootHash = header.stateRoot.toArray, source = storage)
  }
}

class ArchiveStateStorage(private val nodeStorage: NodeStorage) extends StateStorage {

  override def forcePersist(reason: FlushSituation): Boolean = true

  override def onBlockSave(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit =
    updateBestBlocksData()

  override def onBlockRollback(bn: BigInt, currentBestSavedBlock: BigInt)(updateBestBlocksData: () => Unit): Unit =
    updateBestBlocksData()

  override lazy val archivingStorage: MptStorage = new ArchiveMptStorage(nodeStorage)

  override lazy val readOnlyStorage: MptStorage = ReadOnlyMptStorage(nodeStorage)

  override def saveNode(nodeHash: Node.Hash, nodeEncoded: Node.Encoded): Unit =
    nodeStorage.put(nodeHash, nodeEncoded)

  override def getNode(nodeHash: Node.Hash): Option[MptNode] =
    nodeStorage.get(nodeHash).map(_.toMptNode)
}
