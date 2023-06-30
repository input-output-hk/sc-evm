package io.iohk.scevm.mpt

import io.iohk.bytes.ByteString
import io.iohk.ethereum.rlp.RLPImplicitConversions._
import io.iohk.ethereum.rlp.RLPImplicits._
import io.iohk.ethereum.rlp.{encode => rlpEncode, _}
import io.iohk.scevm.utils.Logger

/** This class helps to deal with two problems regarding MptNodes storage:
  * 1) Define a way to delete ones that are no longer needed but allow rollbacks to be performed
  * 2) Avoids removal of nodes that can be used in different trie branches because the hash is the same
  *
  * To deal with (1) when a node is no longer needed, block number alongside with a stored node snapshot is saved so
  * it can be restored in case of rollback.
  *
  * In order to solve (2), before saving a node, it's wrapped with the number of references it has.
  *
  * Using this storage will change data to be stored in mptStorage in two ways (and it will, as consequence, make
  * different pruning mechanisms incompatible):
  * - Instead of saving KEY -> VALUE, it will store KEY -> STORED_NODE(VALUE, REFERENCE_COUNT, LAST_USED_BY_BLOCK)
  *
  * Also, additional data will be saved in this storage:
  * - For each block: BLOCK_NUMBER_TAG -> NUMBER_OF_SNAPSHOTS
  * - For each node changed within a block: (BLOCK_NUMBER_TAG ++ SNAPSHOT_INDEX) -> SNAPSHOT
  *
  * Storing snapshot info this way allows for easy construction of snapshot key (based on a block number
  * and number of snapshots) and therefore, fast access to each snapshot individually.
  */
class ReferenceCountNodeStorage(underlying: MptStorage, bn: BigInt) extends MptStorage {

  import ReferenceCountNodeStorage._

  def get(key: ByteString): Option[Node.Encoded] =
    underlying.get(key).map(node => storedNodeFromBytes(node).nodeEncoded.toArray)

  def update(toRemove: Seq[Node.Hash], toUpsert: Seq[(Node.Hash, Node.Encoded)]): ReferenceCountNodeStorage = {

    val deathRowKey = drRowKey(bn)

    var currentDeathRow = getDeathRow(deathRowKey, underlying)
    // Process upsert changes. As the same node might be changed twice within the same update, we need to keep changes
    // within a map. There is also stored the snapshot version before changes
    val upsertChanges = prepareUpsertChanges(toUpsert, bn)
    val changes       = prepareRemovalChanges(toRemove, upsertChanges, bn)

    val (toUpsertUpdated, snapshots) =
      changes.foldLeft((Seq.empty[(Node.Hash, Node.Encoded)], Seq.empty[StoredNodeSnapshot])) {
        case ((upsertAcc, snapshotAcc), (key, (storedNode, theSnapshot))) =>
          // Update it in DB

          // if after update number references drop to zero mark node as possible for deletion after x blocks
          if (storedNode.references == 0) {
            currentDeathRow = currentDeathRow ++ key
          }

          (upsertAcc :+ (key -> storedNodeToBytes(storedNode)), snapshotAcc :+ theSnapshot)
      }

    val snapshotToSave: Seq[(Node.Hash, Array[Byte])] = getSnapshotsToSave(bn, snapshots)

    val deathRow =
      if (currentDeathRow.nonEmpty)
        Seq(deathRowKey -> currentDeathRow.toArray[Byte])
      else
        Seq()

    underlying.update(Nil, deathRow ++ toUpsertUpdated ++ snapshotToSave)
    this
  }

  private def prepareUpsertChanges(toUpsert: Seq[(Node.Hash, Node.Encoded)], blockNumber: BigInt): Changes =
    toUpsert.foldLeft(Map.empty[Node.Hash, (StoredNode, StoredNodeSnapshot)]) { (storedNodes, toUpsertItem) =>
      val (nodeKey, nodeEncoded) = toUpsertItem
      val (storedNode, snapshot) = getFromChangesOrStorage(nodeKey, storedNodes)
        .getOrElse(
          StoredNode.withoutReferences(nodeEncoded) -> StoredNodeSnapshot(nodeKey, None)
        ) // if it's new, return an empty stored node

      storedNodes + (nodeKey -> ((storedNode.incrementReferences(1, blockNumber), snapshot)))
    }

  private def prepareRemovalChanges(
      toRemove: Seq[Node.Hash],
      changes: Map[Node.Hash, (StoredNode, StoredNodeSnapshot)],
      blockNumber: BigInt
  ): Changes =
    toRemove.foldLeft(changes) { (storedNodes, nodeKey) =>
      val maybeStoredNode: Option[(StoredNode, StoredNodeSnapshot)] = getFromChangesOrStorage(nodeKey, storedNodes)

      maybeStoredNode.fold(storedNodes) { case (storedNode, snapshot) =>
        storedNodes + (nodeKey -> ((storedNode.decrementReferences(1, blockNumber), snapshot)))
      }
    }

  private def getSnapshotsToSave(
      blockNumber: BigInt,
      snapshots: Seq[StoredNodeSnapshot]
  ): Seq[(Node.Hash, Array[Byte])] =
    if (snapshots.nonEmpty) {
      // If not empty, snapshots will be stored indexed by block number and index
      val snapshotCountKey = getSnapshotsCountKey(blockNumber)
      val getSnapshotKeyFn = getSnapshotKey(blockNumber)(_)
      val blockNumberSnapshotsCount: BigInt =
        underlying.get(snapshotCountKey).map(snapshotsCountFromBytes).getOrElse(0)
      val snapshotsToSave = snapshots.zipWithIndex.map { case (snapshot, index) =>
        getSnapshotKeyFn(blockNumberSnapshotsCount + index) -> snapshotToBytes(snapshot)
      }
      // Save snapshots and latest snapshot index
      (snapshotCountKey -> snapshotsCountToBytes(blockNumberSnapshotsCount + snapshotsToSave.size)) +: snapshotsToSave
    } else Nil

  private def getFromChangesOrStorage(
      nodeKey: Node.Hash,
      storedNodes: Changes
  ): Option[(StoredNode, StoredNodeSnapshot)] =
    storedNodes
      .get(nodeKey)
      .orElse(underlying.get(nodeKey).map(storedNodeFromBytes).map(sn => sn -> StoredNodeSnapshot(nodeKey, Some(sn))))
}

object ReferenceCountNodeStorage extends Logger {

  val nodeKeyLength = 32

  def drRowKey(bn: BigInt): ByteString =
    ByteString("dr".getBytes()) ++ ByteString(bn.toByteArray)

  def getDeathRow(key: ByteString, mptStorage: MptStorage): ByteString =
    ByteString(mptStorage.get(key).getOrElse(Array[Byte]()))

  type Changes = Map[Node.Hash, (StoredNode, StoredNodeSnapshot)]

  /** Looks for the StoredNode snapshots based on block number and saves (or deletes) them
    *
    * @param blockNumber BlockNumber to rollback
    * @param mptStorage NodeStorage
    */
  def rollback(blockNumber: BigInt, mptStorage: MptStorage): Unit =
    withSnapshotCount(blockNumber, mptStorage) { (snapshotsCountKey, snapshotCount) =>
      // Get all the snapshots
      val snapshots = snapshotKeysUpTo(blockNumber, snapshotCount)
        .flatMap(key => mptStorage.get(key).map(snapshotFromBytes))
      // We need to delete deathrow for rollbacked block
      val deathRowKey = drRowKey(blockNumber)
      // Transform them to db operations
      val (toRemove, toUpsert) = snapshots.foldLeft((Seq.empty[Node.Hash], Seq.empty[(Node.Hash, Node.Encoded)])) {
        // Undo Actions
        case ((r, u), StoredNodeSnapshot(nodeHash, Some(sn))) => (r, (nodeHash -> storedNodeToBytes(sn)) +: u)
        case ((r, u), StoredNodeSnapshot(nodeHash, None))     => (nodeHash +: r, u)
      }
      // also remove snapshot as we have done a rollback
      mptStorage.update(toRemove :+ snapshotsCountKey :+ deathRowKey, toUpsert)
    }

  private def withSnapshotCount(blockNumber: BigInt, mptStorage: MptStorage)(
      f: (ByteString, BigInt) => Unit
  ): Unit = {
    val snapshotsCountKey = getSnapshotsCountKey(blockNumber)
    // Look for snapshot count for given block number
    val maybeSnapshotCount = mptStorage.get(snapshotsCountKey).map(snapshotsCountFromBytes)
    maybeSnapshotCount match {
      case Some(snapshotCount) => f(snapshotsCountKey, snapshotCount)
      case None                => ()
    }
  }

  private def snapshotKeysUpTo(blockNumber: BigInt, snapshotCount: BigInt): Seq[ByteString] = {
    val getSnapshotKeyFn = getSnapshotKey(blockNumber)(_)
    (BigInt(0) until snapshotCount).map(snapshotIndex => getSnapshotKeyFn(snapshotIndex))
  }

  /** Wrapper of MptNode in order to store number of references it has.
    *
    * @param nodeEncoded Encoded Mpt Node to be used in MerklePatriciaTrie
    * @param references  Number of references the node has. Each time it's updated references are increased and everytime it's deleted, decreased
    * @param lastUsedByBlock Block Number where this node was last used
    */
  final case class StoredNode(nodeEncoded: ByteString, references: Int, lastUsedByBlock: BigInt) {
    def incrementReferences(amount: Int, blockNumber: BigInt): StoredNode =
      copy(references = references + amount, lastUsedByBlock = blockNumber)

    def decrementReferences(amount: Int, blockNumber: BigInt): StoredNode =
      copy(references = references - amount, lastUsedByBlock = blockNumber)
  }

  object StoredNode {
    def withoutReferences(nodeEncoded: Array[Byte]): StoredNode = new StoredNode(ByteString(nodeEncoded), 0, 0)
  }

  /** Key to be used to store BlockNumber -> Snapshots Count
    *
    * @param blockNumber Block Number Tag
    * @return Key
    */
  private def getSnapshotsCountKey(blockNumber: BigInt): ByteString = ByteString(
    "sck".getBytes ++ blockNumber.toByteArray
  )

  /** Returns a snapshot key given a block number and a snapshot index
    * @param blockNumber Block Number Ta
    * @param index Snapshot Index
    * @return
    */
  private def getSnapshotKey(blockNumber: BigInt)(index: BigInt): ByteString = ByteString(
    ("sk".getBytes ++ blockNumber.toByteArray) ++ index.toByteArray
  )

  /** Used to store a node snapshot in the db. This will be used to rollback a transaction.
    * @param nodeKey Node's key
    * @param storedNode Stored node that can be rolledback. If None, it means that node wasn't previously in the DB
    */
  final case class StoredNodeSnapshot(nodeKey: Node.Hash, storedNode: Option[StoredNode])

  private def snapshotsCountFromBytes(encoded: Array[Byte]): BigInt = decode(encoded)(bigIntEncDec)

  private def storedNodeFromBytes(encoded: Array[Byte]): StoredNode = decode(encoded)(storedNodeEncDec)

  private def snapshotFromBytes(encoded: Array[Byte]): StoredNodeSnapshot = decode(encoded)(snapshotEncDec)

  private def snapshotsCountToBytes(value: BigInt): Array[Byte] = rlpEncode(value)(bigIntEncDec)

  private def storedNodeToBytes(storedNode: StoredNode): Array[Byte] = rlpEncode(
    storedNodeEncDec.encode(storedNode)
  )

  private def snapshotToBytes(snapshot: StoredNodeSnapshot): Array[Byte] = rlpEncode(
    snapshotEncDec.encode(snapshot)
  )

  private val storedNodeEncDec = new RLPDecoder[StoredNode] with RLPEncoder[StoredNode] {
    override def decode(rlp: RLPEncodeable): StoredNode = rlp match {
      case RLPList(nodeEncoded, references, lastUsedByBlock) => StoredNode(nodeEncoded, references, lastUsedByBlock)
      case _                                                 => throw new RuntimeException("Error when decoding stored node")
    }

    override def encode(obj: StoredNode): RLPEncodeable = RLPList(obj.nodeEncoded, obj.references, obj.lastUsedByBlock)
  }

  private val snapshotEncDec = new RLPDecoder[StoredNodeSnapshot] with RLPEncoder[StoredNodeSnapshot] {
    override def decode(rlp: RLPEncodeable): StoredNodeSnapshot = rlp match {
      case RLPList(nodeHash, storedNode) =>
        StoredNodeSnapshot(byteStringFromEncodeable(nodeHash), Some(storedNodeFromBytes(storedNode)))
      case RLPValue(nodeHash) => StoredNodeSnapshot(byteStringFromEncodeable(nodeHash), None)
      case _                  => throw new RuntimeException("Error when decoding stored nodes")
    }

    override def encode(objs: StoredNodeSnapshot): RLPEncodeable = objs match {
      case StoredNodeSnapshot(nodeHash, Some(storedNode)) =>
        RLPList(byteStringToEncodeable(nodeHash), storedNodeToBytes(storedNode))
      case StoredNodeSnapshot(nodeHash, None) => RLPValue(byteStringToEncodeable(nodeHash))
    }
  }

}
