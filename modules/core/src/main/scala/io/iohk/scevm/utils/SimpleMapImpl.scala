package io.iohk.scevm.utils

/** Basic immutable implementation of SimpleMap
  *
  * @param contents immutable map (from Scala standard library)
  */
final case class SimpleMapImpl[K, V](contents: Map[K, V]) extends SimpleMap[K, V, SimpleMapImpl[K, V]] {
  override def get(key: K): Option[V] = contents.get(key)

  override def update(toRemove: Seq[K], toUpsert: Seq[(K, V)]): SimpleMapImpl[K, V] =
    SimpleMapImpl(
      toUpsert.foldLeft(contents.removedAll(toRemove)) { (updated, toUpsert) =>
        updated + toUpsert
      }
    )
}

object SimpleMapImpl {
  def empty[K, V]: SimpleMapImpl[K, V] = SimpleMapImpl(Map.empty)
}
