package io.iohk.scevm.db

import com.typesafe.config.Config

import java.nio.file.Path

sealed trait DataSourceConfig

object DataSourceConfig {
  final case class RocksDbConfig(
      createIfMissing: Boolean,
      paranoidChecks: Boolean,
      path: Path,
      maxThreads: Int,
      maxOpenFiles: Int,
      verifyChecksums: Boolean,
      levelCompaction: Boolean,
      blockSize: Long,
      blockCacheSize: Long,
      backupDir: Path
  )                          extends DataSourceConfig
  case object MemoryDbConfig extends DataSourceConfig

  object RocksDbConfig {
    def fromConfig(rocksDbConfig: Config): RocksDbConfig =
      RocksDbConfig(
        createIfMissing = rocksDbConfig.getBoolean("create-if-missing"),
        paranoidChecks = rocksDbConfig.getBoolean("paranoid-checks"),
        path = Path.of(rocksDbConfig.getString("path")),
        maxThreads = rocksDbConfig.getInt("max-threads"),
        maxOpenFiles = rocksDbConfig.getInt("max-open-files"),
        verifyChecksums = rocksDbConfig.getBoolean("verify-checksums"),
        levelCompaction = rocksDbConfig.getBoolean("level-compaction-dynamic-level-bytes"),
        blockSize = rocksDbConfig.getLong("block-size"),
        blockCacheSize = rocksDbConfig.getLong("block-cache-size"),
        backupDir = Path.of(rocksDbConfig.getString("backup-dir"))
      )
  }

  def fromConfig(rootConfig: Config): DataSourceConfig = {
    val dbConfig = rootConfig.getConfig("db")
    val source   = DataSourceType(dbConfig.getString("data-source"))
    source match {
      case DataSourceType.RocksDb => RocksDbConfig.fromConfig(dbConfig.getConfig("rocksdb"))
      case DataSourceType.Memory  => MemoryDbConfig
    }
  }

  sealed trait DataSourceType
  object DataSourceType {
    case object RocksDb extends DataSourceType
    case object Memory  extends DataSourceType

    def apply(value: String): DataSourceType = value match {
      case "rocksdb" => RocksDb
      case "memory"  => Memory
      case _         => throw new RuntimeException("""Only "rocksdb" or "memory" datasource are supported""")
    }
  }
}
