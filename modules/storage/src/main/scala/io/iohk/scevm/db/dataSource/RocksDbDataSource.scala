package io.iohk.scevm.db.dataSource

import cats.effect.Sync
import cats.effect.kernel.Resource
import io.iohk.scevm.db.DataSourceConfig.RocksDbConfig
import io.iohk.scevm.db.dataSource.DataSource._
import io.iohk.scevm.db.dataSource.RocksDbDataSource._
import io.iohk.scevm.utils.Logger
import io.iohk.scevm.utils.TryWithResources.withResources
import org.rocksdb._

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.locks.{ReentrantLock, ReentrantReadWriteLock}
import scala.collection.immutable.ArraySeq
import scala.collection.mutable
import scala.util.control.NonFatal
import scala.util.{Success, Try, Using}

class RocksDbDataSource(
    db: RocksDB,
    rocksDbConfig: RocksDbConfig,
    readOptions: ReadOptions,
    dbOptions: DBOptions,
    cfOptions: ColumnFamilyOptions,
    handles: Map[Namespace, ColumnFamilyHandle]
) extends DataSource
    with Logger {

  @volatile
  private var isClosed = false

  private val backupLock = new ReentrantLock()

  /** This function obtains the associated value to a key, if there exists one.
    *
    * @param namespace which will be searched for the key.
    * @param key       the key retrieve the value.
    * @return the value associated with the passed key.
    */
  override def get(namespace: Namespace, key: Key): Option[Value] = {
    dbLock.readLock().lock()
    try {
      assureNotClosed()
      val byteArray = db.get(handles(namespace), readOptions, key.toArray)
      Option(ArraySeq.unsafeWrapArray(byteArray))
    } catch {
      case error: RocksDbDataSourceClosedException =>
        throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(
          s"Not found associated value to a namespace: $namespace and a key: $key",
          error
        )
    } finally dbLock.readLock().unlock()
  }

  /** This function obtains the associated value to a key, if there exists one. It assumes that
    * caller already properly serialized key. Useful when caller knows some pattern in data to
    * avoid generic serialization.
    *
    * @param key the key retrieve the value.
    * @return the value associated with the passed key.
    */
  override def getOptimized(namespace: Namespace, key: Array[Byte]): Option[Array[Byte]] = {
    dbLock.readLock().lock()
    try {
      assureNotClosed()
      Option(db.get(handles(namespace), readOptions, key))
    } catch {
      case error: RocksDbDataSourceClosedException =>
        throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(
          s"Not found associated value to a key: ${key.mkString("Array(", ", ", ")")}",
          error
        )
    } finally dbLock.readLock().unlock()
  }

  /** @throws RocksDbDataSourceClosedException Hello
    * @throws RocksDbDataSourceException World
    */
  override def update(dataSourceUpdates: Seq[DataUpdate]): Unit = {
    dbLock.writeLock().lock()
    try {
      assureNotClosed()
      withResources(new WriteOptions()) { writeOptions =>
        withResources(new WriteBatch()) { batch =>
          dataSourceUpdates.foreach {
            case DataSourceUpdate(namespace, toRemove, toUpsert) =>
              toRemove.foreach { key =>
                batch.delete(handles(namespace), key.toArray)
              }
              toUpsert.foreach { case (k, v) => batch.put(handles(namespace), k.toArray, v.toArray) }

            case DataSourceUpdateOptimized(namespace, toRemove, toUpsert) =>
              toRemove.foreach { key =>
                batch.delete(handles(namespace), key)
              }
              toUpsert.foreach { case (k, v) => batch.put(handles(namespace), k, v) }
          }
          db.write(writeOptions, batch)
        }
      }
    } catch {
      case error: RocksDbDataSourceClosedException =>
        throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"DataSource not updated", error)
    } finally dbLock.writeLock().unlock()
  }

  /** This function closes the DataSource, without deleting the files used by it.
    */
  def close(): Unit = {
    log.info(s"About to close DataSource in path: ${rocksDbConfig.path}")
    dbLock.writeLock().lock()
    try {
      assureNotClosed()
      isClosed = true
      // There is specific order for closing rocksdb with column families descibed in
      // https://github.com/facebook/rocksdb/wiki/RocksJava-Basics#opening-a-database-with-column-families
      // 1. Free all column families handles
      handles.values.foreach(_.close())
      // 2. Free db and db options
      db.close()
      readOptions.close()
      dbOptions.close()
      // 3. Free column families options
      cfOptions.close()
      log.info(s"DataSource closed successfully in the path: ${rocksDbConfig.path}")
    } catch {
      case error: RocksDbDataSourceClosedException =>
        throw error
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"Not closed the DataSource properly", error)
    } finally dbLock.writeLock().unlock()
  }

  private def assureNotClosed(): Unit =
    if (isClosed) {
      throw RocksDbDataSourceClosedException(s"This ${getClass.getSimpleName} has been closed")
    }

  def backup(): Try[Either[String, Path]] =
    if (backupLock.tryLock()) {
      try if (Files.exists(rocksDbConfig.backupDir)) {
        Success(
          Left(s"The backup target folder '${rocksDbConfig.backupDir}' already exist. Please delete it or rename it.")
        )
      } else {
        Using(Checkpoint.create(db)) { checkpoint =>
          // We create a sibling folder to ensure that we are on the same filesystem
          // This allows rocksdb to create hardlink for sst files and makes the backup very fast
          // The folder is then renamed atomically so that an external process can check when the backup
          // directories appear. This ensure that the backup in the backup dir is always consistent and
          // we don't upload some partial backup by mistake.
          val tempPath = Path.of(rocksDbConfig.backupDir.toString + ".tmp")
          checkpoint.createCheckpoint(tempPath.toString)
          Files.move(tempPath, rocksDbConfig.backupDir, StandardCopyOption.ATOMIC_MOVE)
          Right(rocksDbConfig.backupDir)
        }
      } finally backupLock.unlock()
    } else {
      Success(Left("A backup process is already running."))
    }
}

object RocksDbDataSource {
  final case class RocksDbDataSourceClosedException(message: String) extends IllegalStateException(message)
  final case class RocksDbDataSourceException(message: String, cause: Throwable)
      extends RuntimeException(message, cause)

  /** The rocksdb implementation acquires a lock from the operating system to prevent misuse
    */
  private val dbLock = new ReentrantReadWriteLock()

  // scalastyle:off method.length
  private def createDB(
      rocksDbConfig: RocksDbConfig,
      namespaces: Seq[Namespace]
  ): (RocksDB, mutable.Buffer[ColumnFamilyHandle], ReadOptions, DBOptions, ColumnFamilyOptions) = {
    import rocksDbConfig._

    import scala.jdk.CollectionConverters._

    RocksDB.loadLibrary()

    RocksDbDataSource.dbLock.writeLock().lock()
    try {
      val readOptions = new ReadOptions().setVerifyChecksums(rocksDbConfig.verifyChecksums)

      val tableCfg = new BlockBasedTableConfig()
        .setBlockSize(blockSize)
        .setBlockCache(new ClockCache(blockCacheSize))
        .setCacheIndexAndFilterBlocks(true)
        .setPinL0FilterAndIndexBlocksInCache(true)
        .setFilterPolicy(new BloomFilter(10, false))

      val options = new DBOptions()
        .setCreateIfMissing(createIfMissing)
        .setParanoidChecks(paranoidChecks)
        .setMaxOpenFiles(maxOpenFiles)
        .setIncreaseParallelism(maxThreads)
        .setCreateMissingColumnFamilies(true)

      val cfOpts =
        new ColumnFamilyOptions()
          .setCompressionType(CompressionType.LZ4_COMPRESSION)
          .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
          .setLevelCompactionDynamicLevelBytes(levelCompaction)
          .setTableFormatConfig(tableCfg)

      val cfDescriptors = List(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts)) ++ namespaces.map {
        namespace =>
          new ColumnFamilyDescriptor(namespace.toArray, cfOpts)
      }

      val columnFamilyHandleList = mutable.Buffer.empty[ColumnFamilyHandle]

      (
        RocksDB.open(options, path.toString, cfDescriptors.asJava, columnFamilyHandleList.asJava),
        columnFamilyHandleList,
        readOptions,
        options,
        cfOpts
      )
    } catch {
      case NonFatal(error) =>
        throw RocksDbDataSourceException(s"Not created the DataSource properly", error)
    } finally RocksDbDataSource.dbLock.writeLock().unlock()
  }

  def apply[F[_]: Sync](rocksDbConfig: RocksDbConfig, namespaces: Seq[Namespace]): Resource[F, RocksDbDataSource] =
    Resource.make[F, RocksDbDataSource](
      Sync[F].delay {
        val allNameSpaces                                    = Seq(RocksDB.DEFAULT_COLUMN_FAMILY.toIndexedSeq) ++ namespaces
        val (db, handles, readOptions, dbOptions, cfOptions) = createDB(rocksDbConfig, namespaces)
        require(allNameSpaces.size == handles.size)
        val handlesMap = allNameSpaces.zip(handles.toList).toMap
        //This assert ensures that we do not have duplicated namespaces
        require(handlesMap.size == handles.size)

        new RocksDbDataSource(db, rocksDbConfig, readOptions, dbOptions, cfOptions, handlesMap)
      }
    )(ds => Sync[F].delay(ds.close()))
}
