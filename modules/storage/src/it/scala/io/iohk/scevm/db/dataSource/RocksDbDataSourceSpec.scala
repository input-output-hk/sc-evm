package io.iohk.scevm.db.dataSource

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.iohk.scevm.db.DataSourceConfig.RocksDbConfig
import io.iohk.scevm.db.storage.Namespaces
import org.rocksdb.RocksDB
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files
import scala.util.Success

class RocksDbDataSourceSpec extends AnyWordSpec with Matchers with ScalaFutures {

  "Backup should produce a functional database" in {
    val initialDir = Files.createTempDirectory("initial")
    val backupDir  = Files.createTempDirectory("backedUp").resolve("backup")
    val rocksDbConfig = RocksDbConfig(
      createIfMissing = true,
      paranoidChecks = true,
      path = initialDir,
      maxThreads = 1,
      maxOpenFiles = 32,
      verifyChecksums = true,
      levelCompaction = true,
      blockSize = 16384,
      blockCacheSize = 33554432,
      backupDir = backupDir
    )
    val initialDataSource: RocksDbDataSource =
      RocksDbDataSource[IO](rocksDbConfig, Namespaces.nsSeq).allocated.unsafeRunSync()._1

    val values = Seq(
      (IndexedSeq[Byte](1), IndexedSeq[Byte](2)),
      (IndexedSeq[Byte](2), IndexedSeq[Byte](4)),
      (IndexedSeq[Byte](3), IndexedSeq[Byte](6)),
      (IndexedSeq[Byte](4), IndexedSeq[Byte](8)),
      (IndexedSeq[Byte](5), IndexedSeq[Byte](10))
    )

    // Given an initially populated database that is backed up
    try {
      initialDataSource.update(Seq(DataSourceUpdate(RocksDB.DEFAULT_COLUMN_FAMILY.toIndexedSeq, Nil, values)))
      initialDataSource.backup() shouldBe Success(Right(backupDir))
    } finally initialDataSource.close()

    val secondDataSource: RocksDbDataSource =
      RocksDbDataSource[IO](
        rocksDbConfig.copy(createIfMissing = false, path = backupDir),
        Namespaces.nsSeq
      ).allocated
        .unsafeRunSync()
        ._1

    try for (value <- values)
      secondDataSource.get(RocksDB.DEFAULT_COLUMN_FAMILY.toIndexedSeq, value._1) shouldBe Some(value._2)
    finally secondDataSource.close()
  }

}
