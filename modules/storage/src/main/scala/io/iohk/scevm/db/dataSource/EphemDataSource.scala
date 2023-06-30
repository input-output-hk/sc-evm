package io.iohk.scevm.db.dataSource

import io.iohk.scevm.db.dataSource.DataSource._
import io.iohk.scevm.utils.SimpleMapImpl

import java.nio.ByteBuffer
import java.nio.file.Path
import scala.util.{Success, Try}

class EphemDataSource(var storage: SimpleMapImpl[ByteBuffer, Value]) extends DataSource {

  /** key.drop to remove namespace prefix from the key
    * @return key values paris from this storage
    */
  def getAll(namespace: Namespace): Seq[(Key, Value)] = synchronized {
    storage.contents.toSeq.map { case (key, value) => unwrap(namespace, key) -> value }
  }

  override def get(namespace: Namespace, key: Key): Option[Value] = synchronized {
    storage.get(wrap(namespace, key))
  }

  override def getOptimized(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]] =
    get(namespace, key.toIndexedSeq).map(_.toArray)

  override def update(dataSourceUpdates: Seq[DataUpdate]): Unit = synchronized {
    dataSourceUpdates.foreach {
      case DataSourceUpdate(namespace, toRemove, toUpsert) =>
        update(namespace, toRemove, toUpsert)
      case DataSourceUpdateOptimized(namespace, toRemove, toUpsert) =>
        update(namespace, toRemove.map(_.toIndexedSeq), toUpsert.map(s => (s._1.toIndexedSeq, s._2.toIndexedSeq)))
    }
  }

  private def update(namespace: Namespace, toRemove: Seq[Key], toUpsert: Seq[(Key, Value)]): Unit =
    storage = storage.update(
      toRemove.map(wrap(namespace, _)),
      toUpsert.map { case (key, value) => wrap(namespace, key) -> value }
    )

  private def wrap(namespace: Namespace, key: Key): ByteBuffer =
    ByteBuffer.wrap((namespace ++ key).toArray)

  private def unwrap(namespace: Namespace, wrappedKey: ByteBuffer): Key =
    wrappedKey.array().drop(namespace.length).toIndexedSeq

  override def backup(): Try[Either[String, Path]] = Success(Left("Backup is not supported for in memory storage"))
}

object EphemDataSource {
  def apply(): EphemDataSource = new EphemDataSource(SimpleMapImpl.empty)
}
