package io.iohk.scevm.db.storage

import cats.effect.IO
import io.iohk.bytes.ByteString
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.mpt.{ArchiveMptStorage, MptStorage}
import io.iohk.scevm.storage.metrics.NoOpStorageMetrics
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class StateStorageSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {

  import io.iohk.scevm.testing.Generators._
  import io.iohk.scevm.mpt.Generators._

  def saveNodeToDbTest(storage: StateStorage, mptStorage: MptStorage): Unit =
    forAll(keyValueByteStringGen(32)) { keyvals =>
      keyvals.foreach { case (key, value) =>
        storage.saveNode(key, value)
      }

      keyvals.foreach { case (key, value) =>
        val result = mptStorage.get(key)
        assert(result.isDefined)
        assert(result.get.sameElements(value))
      }
    }

  def getNodeFromDbTest(stateStorage: StateStorage): Unit =
    forAll(nodeGen) { node =>
      val storage = stateStorage.archivingStorage
      storage.updateNodesInStorage(Some(node), Nil)
      val fromStorage = stateStorage.getNode(ByteString(node.hash))
      assert(fromStorage.isDefined)
      assert(fromStorage.get == node)
    }

  def provideStorageForTrieTest(stateStorage: StateStorage): Unit =
    forAll(nodeGen) { node =>
      val storage = stateStorage.archivingStorage
      storage.updateNodesInStorage(Some(node), Nil)
      val fromStorage = storage.getNodeOrFail(node.hash)
      assert(fromStorage.hash.sameElements(node.hash))
    }

  "ArchiveStateStorage" should "save node directly to db" in new TestSetup {
    saveNodeToDbTest(archiveStateStorage, archiveNodeStorage)
  }

  it should "provide storage for trie" in new TestSetup {
    provideStorageForTrieTest(archiveStateStorage)
  }

  it should "enable way to get node directly" in new TestSetup {
    getNodeFromDbTest(archiveStateStorage)
  }

  it should "provide function to act on block save" in new TestSetup {
    var ints = List.empty[Int]

    forAll(listOfNodes(minNodes, maxNodes)) { nodes =>
      val storage = archiveStateStorage.archivingStorage
      nodes.foreach(node => storage.updateNodesInStorage(Some(node), Nil))

      val sizeBefore = ints.size
      archiveStateStorage.onBlockSave(1, 0) { () =>
        ints = 1 :: ints
      }

      assert(ints.size == sizeBefore + 1)
    }
  }

  it should "provide function to act on block rollback" in new TestSetup {
    var ints = List.empty[Int]

    forAll(listOfNodes(minNodes, maxNodes)) { nodes =>
      val storage = archiveStateStorage.archivingStorage
      nodes.foreach(node => storage.updateNodesInStorage(Some(node), Nil))

      val sizeBefore = ints.size
      archiveStateStorage.onBlockRollback(1, 0) { () =>
        ints = 1 :: ints
      }

      assert(ints.size == sizeBefore + 1)
    }
  }

  trait TestSetup {
    val minNodes                               = 5
    val maxNodes                               = 15
    val dataSource: EphemDataSource            = EphemDataSource()
    val storageMetrics: NoOpStorageMetrics[IO] = NoOpStorageMetrics[IO]()
    val nodeStorage                            = new NodeStorage(dataSource, storageMetrics.readTimeForNode, storageMetrics.writeTimeForNode)
    val archiveNodeStorage                     = new ArchiveMptStorage(nodeStorage)
    val archiveStateStorage: StateStorage      = StateStorage(nodeStorage)
  }
}
