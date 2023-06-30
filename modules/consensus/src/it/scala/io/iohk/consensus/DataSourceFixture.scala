package io.iohk.consensus

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.iohk.scevm.db.DataSourceConfig.RocksDbConfig
import io.iohk.scevm.db.dataSource.RocksDbDataSource
import io.iohk.scevm.db.storage.Namespaces
import org.scalatest.{BeforeAndAfterAll, Suite}

import java.nio.file.{Files, Path}

trait DataSourceFixture extends BeforeAndAfterAll { this: Suite =>

  lazy val dataSource = createDataSource

  override protected def afterAll(): Unit =
    try super.afterAll()
    finally dataSource.close()

  private def createDataSource: RocksDbDataSource = {
    val dbPath = Files.createTempDirectory("temp-it-test")
    val rocksDbConfig = RocksDbConfig(
      createIfMissing = true,
      paranoidChecks = true,
      path = dbPath.toAbsolutePath,
      maxThreads = 1,
      maxOpenFiles = 32,
      verifyChecksums = true,
      levelCompaction = true,
      blockSize = 16384,
      blockCacheSize = 33554432,
      backupDir = Path.of("/dev/null")
    )
    RocksDbDataSource[IO](rocksDbConfig, Namespaces.nsSeq).allocated.unsafeRunSync()._1
  }

}
