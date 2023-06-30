package io.iohk.scevm.mpt

import io.iohk.scevm.utils.InMemorySimpleMapProxy

/** This storage allows to read from another NodesKeyValueStorage but doesn't remove or upsert into database.
  * To do so, it uses a variable InMemorySimpleMapProxy wrapping the backing MptStorage.
  */
class ReadOnlyMptStorage private (wrapped: MptStorage) extends MptStorage {
  private var proxy = InMemorySimpleMapProxy.wrap[Node.Hash, Node.Encoded, MptStorage](wrapped)

  override def get(key: Node.Hash): Option[Node.Encoded] =
    synchronized {
      proxy.get(key)
    }

  override def update(toRemove: Seq[Node.Hash], toUpsert: Seq[(Node.Hash, Node.Encoded)]): MptStorage =
    synchronized {
      proxy = proxy.update(toRemove, toUpsert)
      this
    }
}

object ReadOnlyMptStorage {
  def apply(mptStorage: MptStorage): ReadOnlyMptStorage =
    new ReadOnlyMptStorage(mptStorage)
}
