package io.iohk.scevm.exec.mempool

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits.catsSyntaxFlatMapOps
import io.iohk.scevm.domain.{SignedTransaction, Slot, TransactionHash}
import io.iohk.scevm.exec.metrics.NoOpExecutionMetrics
import io.iohk.scevm.storage.execution.SlotBasedMempool
import io.iohk.scevm.testing.TransactionGenerators.signedTxSeqGenFixed
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalacheck.Gen
import org.scalatest.compatible.Assertion
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.util.Random

class SignedTransactionMempoolSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with IOSupport
    with NormalPatience {

  import SignedTransactionMempoolSpec._
  import SlotBasedMempool.{Added, NotAdded, NotRemoved, Removed}
  import io.iohk.scevm.testing.Generators.intGen
  import io.iohk.scevm.testing.TransactionGenerators.{signedTxGen, signedTxSeqGen}

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(sizeRange = 10)

  "SignedTransactionMempool" when {
    "not considering ttl" must {
      "add a single SignedTransaction to an empty mempool" in forAll(signedTxGen()) { stxToBeAdded =>
        assertIOSync {
          withEmptyMempool { mempool =>
            mempool.add(stxToBeAdded, dummySlot).map {
              case Added(stx, slot) =>
                assert(stx === stxToBeAdded)
                assert(slot === dummySlot)
              case r => fail(s"Invalid result $r")
            }
          }
        }
      }
      "not add the same SignedTransaction twice" in forAll(signedTxGen()) { stxToBeAdded =>
        assertIOSync {
          withEmptyMempool { mempool =>
            mempool
              .add(stxToBeAdded, dummySlot)
              .flatMap(_ => mempool.add(stxToBeAdded, dummySlot))
              .map {
                case NotAdded(stx, reason) =>
                  assert(stx === stxToBeAdded)
                  assert(reason === "Transaction already in pool.")
                case r => fail(s"Invalid result $r")
              }
          }
        }
      }
      "add multiple SignedTransactions" in {
        forAll(signedTxSeqGen(30), initialMempoolGen(30)) { (stxsToBeAdded, initial) =>
          assertIOSync {
            withMempool(initial).flatMap { mempool =>
              mempool.addAll(stxsToBeAdded, dummySlot).map { res =>
                assert(res.size === stxsToBeAdded.size)
                val added = res.collect { case a @ Added(_, _) => a }
                added.map(_.elem) must contain theSameElementsAs stxsToBeAdded
                assert(added.forall { case Added(_, slot) => slot == dummySlot })
              }
            }
          }
        }
      }
      "add multiple SignedTransactions, some of them already existing" in {
        forAll(signedTxSeqGen(30), initialMempoolGen(30), intGen(1, 10)) { (stxsToBeAdded, initial, numExisting) =>
          assertIOSync {
            val existing    = stxsToBeAdded.take(numExisting)
            val nonExisting = stxsToBeAdded.drop(numExisting)

            def test(mempool: SignedTransactionMempool[IO]): IO[Assertion] =
              mempool
                .addAll(stxsToBeAdded, dummySlot)
                .map { res =>
                  val added    = res.collect { case Added(stx, _) => stx }
                  val notAdded = res.collect { case NotAdded(stx, _) => stx }
                  added must contain theSameElementsAs nonExisting
                  notAdded must contain theSameElementsAs existing
                }

            includeStxs(initial, existing) >>=
              shuffle >>=
              (initial => withMempool(initial)) >>=
              test
          }
        }
      }
      "reject transactions if the pool is full" in {
        val gen = for {
          poolSizeLimit   <- Gen.chooseNum(1, 100)
          initialPoolSize <- Gen.chooseNum(0, poolSizeLimit)
          stxsToBeAdded   <- signedTxSeqGenFixed(poolSizeLimit - initialPoolSize + 1)
          mempool         <- initialMempoolGen(initialPoolSize)
          extraTx         <- signedTxGen()
        } yield (poolSizeLimit, stxsToBeAdded, mempool, extraTx)

        forAll(gen) { case (poolSizeLimit, stxsToBeAdded, initial, extraTx) =>
          assertIOSync {
            withMempool(initial, MempoolConfig(100, poolSizeLimit)).flatMap { mempool =>
              mempool.addAll(stxsToBeAdded, dummySlot).map { res =>
                assert(res.size === stxsToBeAdded.size)
              } >>
                mempool.add(extraTx, dummySlot).map {
                  case NotAdded(tx, reason) =>
                    assert(tx === extraTx)
                    assert(reason === "Transaction pool is full")
                  case Added(_, _) => fail("Transaction should not have been added.")
                }
            }
          }
        }
      }
      "remove a single SignedTransaction" in forAll(
        signedTxGen(),
        initialMempoolGen(100)
      ) { (stxToBeRemoved, initial) =>
        assertIOSync {
          def test(mempool: SignedTransactionMempool[IO]): IO[Assertion] =
            mempool
              .remove(stxToBeRemoved)
              .map {
                case Removed(stx) => assert(stx === stxToBeRemoved)
                case r            => fail(s"Invalid result: $r")
              }

          includeStx(initial, stxToBeRemoved) >>=
            shuffle >>=
            (initial => withMempool(initial)) >>=
            test
        }
      }
      "handle attempt to remove a single SignedTransaction that's not present" in forAll(
        signedTxGen()
      ) { stxToBeRemoved =>
        assertIOSync {
          withEmptyMempool { mempool =>
            mempool.remove(stxToBeRemoved).map {
              case NotRemoved(stx, reason) =>
                assert(stx === stxToBeRemoved)
                assert(reason === "Not present.")
              case r => fail(s"Invalid result $r")
            }
          }
        }
      }
      "remove multiple SignedTransactions" in {
        forAll(signedTxSeqGen(30), initialMempoolGen(30)) { (stxsToBeRemoved, initial) =>
          assertIOSync {
            def test(mempool: SignedTransactionMempool[IO]): IO[Assertion] =
              mempool
                .removeAll(stxsToBeRemoved)
                .map { res =>
                  assert(res.size === stxsToBeRemoved.size)
                  val removed = res.collect { case Removed(stx) => stx }
                  removed must contain theSameElementsAs stxsToBeRemoved
                }

            includeStxs(initial, stxsToBeRemoved) >>=
              shuffle >>=
              (initial => withMempool(initial)) >>=
              test
          }
        }
      }
      "remove multiple SignedTransactions, some of them not present" in {
        forAll(signedTxSeqGen(30), initialMempoolGen(30), intGen(1, 10)) { (stxsToBeRemoved, initial, numNonExisting) =>
          assertIOSync {
            val nonExisting = stxsToBeRemoved.take(numNonExisting)
            val existing    = stxsToBeRemoved.drop(numNonExisting)

            def test(mempool: SignedTransactionMempool[IO]): IO[Assertion] =
              mempool
                .removeAll(stxsToBeRemoved)
                .map { res =>
                  val removed    = res.collect { case Removed(stx) => stx }
                  val notRemoved = res.collect { case NotRemoved(stx, _) => stx }
                  removed must contain theSameElementsAs existing
                  notRemoved must contain theSameElementsAs nonExisting
                }

            includeStxs(initial, existing) >>=
              shuffle >>=
              (initial => withMempool(initial)) >>=
              test
          }
        }
      }
    }
    "considering ttl" must {
      "add a new transaction to the current slot" in forAll(
        signedTxGen(),
        initialMempoolGen(30),
        slotGen(0, 1000)
      ) { (stxToBeAdded, initial, currentSlot) =>
        def test(mempool: SignedTransactionMempool[IO]): IO[Assertion] =
          mempool.add(stxToBeAdded, currentSlot).map {
            case Added(stx, slot) =>
              assert(stx === stxToBeAdded)
              assert(slot === currentSlot)
            case r => fail(s"Invalid result $r")
          }

        assertIOSync {
          withCustomizedMempool(initial, defaultConfig) >>= test
        }
      }
      "add transactions, update slot, add another transaction" in forAll(
        signedTxGen(),
        signedTxGen(),
        initialMempoolGen(30),
        slotGen(0, 1000)
      ) { (stx1, stx2, initial, currentSlot) =>
        val nextSlot = Slot(currentSlot.number + 1)
        def test(mempool: SignedTransactionMempool[IO]): IO[Assertion] =
          for {
            res1 <- mempool.add(stx1, currentSlot)
            _    <- mempool.evict(nextSlot)
            res2 <- mempool.add(stx2, nextSlot)
          } yield {
            res1 match {
              case Added(stx, s) =>
                assert(stx === stx1)
                assert(s === currentSlot)
              case r => fail(s"Invalid result $r")
            }
            res2 match {
              case Added(stx, s) =>
                assert(stx === stx2)
                assert(s === nextSlot)
              case r => fail(s"Invalid result $r")
            }
          }

        assertIOSync {
          withCustomizedMempool(initial, defaultConfig) >>= test
        }
      }
      "not add duplicate transactions even when the slot is different" in forAll(
        signedTxGen(),
        initialMempoolGen(30),
        slotGen(0, 1000)
      ) { (stx1, initial, currentSlot) =>
        val nextSlot = Slot(currentSlot.number + 1)
        def test(mempool: SignedTransactionMempool[IO]): IO[Assertion] =
          for {
            res1 <- mempool.add(stx1, currentSlot)
            _    <- mempool.evict(nextSlot)
            res2 <- mempool.add(stx1, nextSlot)
          } yield {
            res1 match {
              case Added(stx, s) =>
                assert(stx === stx1)
                assert(s === currentSlot)
              case r => fail(s"Invalid result $r")
            }
            res2 match {
              case NotAdded(stx, _) => assert(stx === stx1)
              case r                => fail(s"Invalid result $r")
            }
          }

        assertIOSync {
          withCustomizedMempool(initial, defaultConfig) >>= test
        }
      }
      "evict transactions that have expired" in forAll(
        signedTxSeqGen(),
        slotGen(0, 1000),
        intGen(0, 100)
      ) { (stxs, currentSlot, firstBatchSize) =>
        val ttlRounds     = 10
        val mempoolConfig = MempoolConfig(transactionTtlRounds = ttlRounds, transactionPoolSize = 100)
        val batch1        = stxs.take(firstBatchSize)
        val batch2        = stxs.drop(firstBatchSize)

        val batch1InsertionSlot = currentSlot
        val batch2InsertionSlot = Slot(currentSlot.number + (ttlRounds - 1))
        val batch1EvictedSlot   = Slot(batch1InsertionSlot.number + ttlRounds)
        val batch2EvictedSlot   = Slot(batch2InsertionSlot.number + ttlRounds)

        def test(mempool: SignedTransactionMempool[IO]): IO[Assertion] =
          for {
            _    <- mempool.addAll(batch1, batch1InsertionSlot)
            res1 <- mempool.getAll
            _    <- mempool.evict(batch2InsertionSlot)
            _    <- mempool.addAll(batch2, batch2InsertionSlot)
            res2 <- mempool.getAll
            _    <- mempool.evict(batch1EvictedSlot)
            res3 <- mempool.getAll
            _    <- mempool.evict(batch2EvictedSlot)
            res4 <- mempool.getAll
          } yield {
            res1 must contain theSameElementsAs batch1           // batch1 has been inserted and not evicted
            res2 must contain theSameElementsAs batch1 ++ batch2 // both batches have been inserted and not evicted
            res3 must contain theSameElementsAs batch2           // both batches have been inserted and batch1 is evicted
            res4 mustBe empty                                    // both batches have been inserted and evicted
          }

        assertIOSync {
          withCustomizedMempool(Map.empty, mempoolConfig) >>= test
        }
      }

      "return alive transactions" in forAll(
        signedTxSeqGen(),
        slotGen(0, 1000),
        intGen(0, 100)
      ) { (stxs, currentSlot, firstBatchSize) =>
        val ttlRounds     = 10
        val mempoolConfig = MempoolConfig(transactionTtlRounds = ttlRounds, transactionPoolSize = 100)
        val batch1        = stxs.take(firstBatchSize)
        val batch2        = stxs.drop(firstBatchSize)

        val batch1InsertionSlot = currentSlot
        val batch2InsertionSlot = Slot(currentSlot.number + (ttlRounds - 1))
        val batch1EvictedSlot   = Slot(batch1InsertionSlot.number + ttlRounds)
        val batch2EvictedSlot   = Slot(batch2InsertionSlot.number + ttlRounds)

        def test(mempool: SignedTransactionMempool[IO]): IO[Assertion] =
          for {
            _    <- mempool.addAll(batch1, batch1InsertionSlot)
            res1 <- mempool.getAliveElements(batch1InsertionSlot)
            _    <- mempool.addAll(batch2, batch2InsertionSlot)
            res2 <- mempool.getAliveElements(batch2InsertionSlot)
            res3 <- mempool.getAliveElements(batch1EvictedSlot) // after eviction of batch1
            res4 <- mempool.getAliveElements(batch2EvictedSlot) // after eviction of batch2
          } yield {
            res1 must contain theSameElementsAs batch1           // batch1 has been inserted and not evicted
            res2 must contain theSameElementsAs batch1 ++ batch2 // both batches have been inserted and not evicted
            res3 must contain theSameElementsAs batch2           // both batches have been inserted and batch1 is evicted
            res4 mustBe empty                                    // both batches have been inserted and evicted
          }

        assertIOSync {
          withCustomizedMempool(Map.empty, mempoolConfig) >>= test
        }
      }

    }
  }
}

object SignedTransactionMempoolSpec {
  import io.iohk.scevm.testing.Generators._
  import io.iohk.scevm.testing.TransactionGenerators.signedTxSeqGen

  private val dummySlot: Slot = Slot(1)

  private val defaultConfig    = MempoolConfig(transactionTtlRounds = 100, transactionPoolSize = 200)
  private val executionMetrics = new NoOpExecutionMetrics[IO]()

  private def withEmptyMempool(t: SignedTransactionMempool[IO] => IO[Assertion]): IO[Assertion] = {
    val mempool = SignedTransactionMempool[IO](defaultConfig, executionMetrics).unsafeRunSync()
    t(mempool)
  }

  private def withMempool(
      initial: Map[TransactionHash, (SignedTransaction, Slot)],
      config: MempoolConfig = defaultConfig
  ): IO[SignedTransactionMempool[IO]] =
    withCustomizedMempool(initial, config)

  private def withCustomizedMempool(
      initial: Map[TransactionHash, (SignedTransaction, Slot)],
      config: MempoolConfig
  ): IO[SignedTransactionMempool[IO]] =
    SignedTransactionMempool.createWith(initial, config, executionMetrics)

  private def initialMempoolGen(max: Int): Gen[Map[TransactionHash, (SignedTransaction, Slot)]] =
    for {
      stxs <- signedTxSeqGen(max, None)
    } yield stxs.map(tupled).toMap

  private def slotGen(min: Long, max: Long): Gen[Slot] =
    for {
      slotNumber <- longGen(min, max)
    } yield Slot(slotNumber)

  private def includeStx(
      m: Map[TransactionHash, (SignedTransaction, Slot)],
      stx: SignedTransaction
  ): IO[Map[TransactionHash, (SignedTransaction, Slot)]] =
    IO.pure(m + tupled(stx))

  private def includeStxs(
      m: Map[TransactionHash, (SignedTransaction, Slot)],
      stxs: Seq[SignedTransaction]
  ): IO[Map[TransactionHash, (SignedTransaction, Slot)]] =
    IO.pure(m ++ stxs.map(tupled))

  private def tupled(stx: SignedTransaction): (TransactionHash, (SignedTransaction, Slot)) =
    (stx.hash, (stx, dummySlot))

  private def shuffle(
      m: Map[TransactionHash, (SignedTransaction, Slot)]
  ): IO[Map[TransactionHash, (SignedTransaction, Slot)]] =
    IO.pure(Random.shuffle(m))
}
