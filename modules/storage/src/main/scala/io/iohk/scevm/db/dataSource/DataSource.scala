package io.iohk.scevm.db.dataSource

import cats.effect.kernel.{Resource, Sync}
import io.iohk.scevm.db.DataSourceConfig
import io.iohk.scevm.db.storage.Namespaces

import java.nio.file.Path
import scala.util.Try

trait DataSource {
  import DataSource._

  /** This function obtains the associated value to a key. It requires the (key-value) pair to be in the DataSource
    *
    * @param namespace which will be searched for the key.
    * @param key the key retrieve the value.
    * @return the value associated with the passed key.
    */
  def apply(namespace: Namespace, key: Key): Value = get(namespace, key).get

  /** This function obtains the associated value to a key, if there exists one.
    *
    * @param namespace which will be searched for the key.
    * @param key the key retrieve the value.
    * @return the value associated with the passed key.
    */
  def get(namespace: Namespace, key: Key): Option[Value]

  /** This function obtains the associated value to a key, if there exists one. It assumes that
    * caller already properly serialized key. Useful when caller knows some pattern in data to
    * avoid generic serialization.
    *
    * @param key the key retrieve the value.
    * @return the value associated with the passed key.
    */
  def getOptimized(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]]

  /** This function updates the DataSource by deleting, updating and inserting new (key-value) pairs.
    * Implementations should guarantee that the whole operation is atomic.
    */
  def update(dataSourceUpdates: Seq[DataUpdate]): Unit

  /** Backups the database. The way the backup is done (or supported) is implementation specific.
    * @return an error or Unit on success
    */
  def backup(): Try[Either[String, Path]]
}

object DataSource {
  type Key       = IndexedSeq[Byte]
  type Value     = IndexedSeq[Byte]
  type Namespace = IndexedSeq[Byte]

  def apply[F[_]: Sync](dataSourceConfig: DataSourceConfig): Resource[F, DataSource] =
    dataSourceConfig match {
      case rocksConfig: DataSourceConfig.RocksDbConfig => RocksDbDataSource(rocksConfig, Namespaces.nsSeq)
      case DataSourceConfig.MemoryDbConfig             => Resource.pure(EphemDataSource())
    }
}
