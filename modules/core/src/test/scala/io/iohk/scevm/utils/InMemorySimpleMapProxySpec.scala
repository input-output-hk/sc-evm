package io.iohk.scevm.utils

import io.iohk.scevm.serialization.ByteArraySerializable
import io.iohk.scevm.utils.SimpleMap
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.ByteBuffer

class InMemorySimpleMapProxySpec extends AnyWordSpec with Matchers {

  "InMemoryTrieProxy" should {
    "not write inserts until commit" in new TestSetup {
      val updatedProxy = InMemorySimpleMapProxy.wrap[Int, Int, SimpleMapImpl[Int, Int]](simpleMap).put(1, 1).put(2, 2)

      assertContains(updatedProxy, 1, 1)
      assertContains(updatedProxy, 2, 2)

      assertNotContainsKey(simpleMap, 1)
      assertNotContainsKey(simpleMap, 2)

      val commitedProxy: InMemorySimpleMapProxy[Int, Int, SimpleMapImpl[Int, Int]] = updatedProxy.persist()

      assertContains(commitedProxy.inner, 1, 1)
      assertContains(commitedProxy.inner, 2, 2)
    }

    "not perform removals until commit" in new TestSetup {
      val preloadedMpt = simpleMap.put(1, 1)
      val proxy        = InMemorySimpleMapProxy.wrap[Int, Int, SimpleMapImpl[Int, Int]](preloadedMpt)

      assertContains(preloadedMpt, 1, 1)
      assertContains(proxy, 1, 1)

      val updatedProxy = proxy.remove(1)
      assertNotContainsKey(updatedProxy, 1)
      assertContains(updatedProxy.inner, 1, 1)

      val commitedProxy = updatedProxy.persist()
      assertNotContainsKey(commitedProxy, 1)
      assertNotContainsKey(commitedProxy.inner, 1)
    }

    "not write updates until commit" in new TestSetup {
      val preloadedMpt = simpleMap.put(1, 1)
      val proxy        = InMemorySimpleMapProxy.wrap[Int, Int, SimpleMapImpl[Int, Int]](preloadedMpt)

      assertContains(preloadedMpt, 1, 1)
      assertContains(proxy, 1, 1)
      assertNotContains(preloadedMpt, 1, 2)
      assertNotContains(proxy, 1, 2)

      val updatedProxy = proxy.put(1, 2)
      assertContains(updatedProxy, 1, 2)
      assertNotContains(updatedProxy.inner, 1, 2)

      val commitedProxy = updatedProxy.persist()
      assertContains(commitedProxy, 1, 2)
      assertContains(commitedProxy.inner, 1, 2)
    }

    "handle sequential operations" in new TestSetup {
      val updatedProxy =
        InMemorySimpleMapProxy
          .wrap[Int, Int, SimpleMapImpl[Int, Int]](simpleMap)
          .put(1, 1)
          .remove(1)
          .put(2, 2)
          .put(2, 3)
      assertNotContainsKey(updatedProxy, 1)
      assertContains(updatedProxy, 2, 3)
    }

    "handle batch operations" in new TestSetup {
      val updatedProxy =
        InMemorySimpleMapProxy.wrap[Int, Int, SimpleMapImpl[Int, Int]](simpleMap).update(Seq(1), Seq((2, 2), (2, 3)))
      assertNotContainsKey(updatedProxy, 1)
      assertContains(updatedProxy, 2, 3)
    }

    "not fail when deleting an inexistent value" in new TestSetup {
      assertNotContainsKey(InMemorySimpleMapProxy.wrap[Int, Int, SimpleMapImpl[Int, Int]](simpleMap).remove(1), 1)
    }

    def assertContains[I <: SimpleMap[Int, Int, I]](trie: I, key: Int, value: Int): Unit =
      assert(trie.get(key).isDefined && trie.get(key).get == value)

    def assertNotContains[I <: SimpleMap[Int, Int, I]](trie: I, key: Int, value: Int): Unit =
      assert(trie.get(key).isDefined && trie.get(key).get != value)

    def assertNotContainsKey[I <: SimpleMap[Int, Int, I]](trie: I, key: Int): Unit = assert(trie.get(key).isEmpty)

    trait TestSetup {
      implicit val intByteArraySerializable: ByteArraySerializable[Int] = new ByteArraySerializable[Int] {
        override def toBytes(input: Int): Array[Byte] = {
          val b: ByteBuffer = ByteBuffer.allocate(4)
          b.putInt(input)
          b.array
        }

        override def fromBytes(bytes: Array[Byte]): Int = ByteBuffer.wrap(bytes).getInt()
      }

      val simpleMap: SimpleMapImpl[Int, Int] = SimpleMapImpl.empty[Int, Int]
    }

  }
}
