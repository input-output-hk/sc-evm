package io.iohk.scevm.mpt

/** This storage ignores any removal updates.
  */
class ArchiveMptStorage(underlying: MptStorage) extends MptStorage {

  override def update(toRemove: Seq[Node.Hash], toUpsert: Seq[(Node.Hash, Node.Encoded)]): ArchiveMptStorage = {
    underlying.update(Nil, toUpsert)
    this
  }

  override def get(key: Node.Hash): Option[Node.Encoded] = underlying.get(key)
}
