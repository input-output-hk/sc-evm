package io.iohk.scevm.exec.mempool

import cats.Traverse
import cats.effect.{Async, Ref, Sync}
import cats.syntax.all._
import io.iohk.scevm.domain.{SignedTransaction, Slot, TransactionHash}
import io.iohk.scevm.exec.mempool.SignedTransactionMempool.{K, V}
import io.iohk.scevm.exec.metrics.ExecutionMetrics
import io.iohk.scevm.storage.execution.SlotBasedMempool
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}

import scala.collection.immutable.HashMap

final class SignedTransactionMempool[F[_]: Sync] private (
    private val pool: Ref[F, Map[K, V]],
    private val config: MempoolConfig,
    private val executionMetrics: ExecutionMetrics[F]
) extends SlotBasedMempool[F, SignedTransaction] {

  import SignedTransactionMempool._
  import SlotBasedMempool._

  implicit private val log: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  /** Clean-up transactions older than the configured TTL
    * @param slot the current slot
    */
  override def evict(slot: Slot): F[Unit] = {

    val res =
      calcLastSlotToKeep(slot) >>=
        evictTransactionsBefore >>=
        logEvicted

    res.handleErrorWith(Logger[F].warn(_)("Error while evicting expired transactions.")).void
  }

  /** Return transactions younger than the configured TTL
    * Warning: this doesn't trigger the eviction mechanism
    * @param slot the current slot
    */
  override def getAliveElements(slot: Slot): F[List[SignedTransaction]] = {
    def living(firstAliveSlot: Slot)(entry: (SignedTransaction, Slot)): Boolean =
      entry._2.number >= firstAliveSlot.number
    for {
      rangeStart  <- calcLastSlotToKeep(slot)
      all         <- pool.get.map(_.values)
      liveElements = all.filter(living(rangeStart)).map(_._1).toList
    } yield liveElements
  }

  private def logEvicted(
      evicted: List[RemovalResult[SignedTransaction]]
  ): F[Unit] = {
    def removedTxs: List[Removed[SignedTransaction]]       = evicted.collect { case r @ Removed(_) => r }
    def notRemovedTxs: List[NotRemoved[SignedTransaction]] = evicted.collect { case nr @ NotRemoved(_, _) => nr }

    Logger[F].info(s"Evicted: [${evicted.size}] transactions from the mempool based on configured TTL.") >>
      Logger[F].debug(s"Evicted transactions: $removedTxs") >>
      Logger[F].debug(s"Not evicted transactions: $notRemovedTxs")
  }.whenA(evicted.nonEmpty)

  private def evictTransactionsBefore(s: Slot): F[List[RemovalResult[SignedTransaction]]] = {
    def expired(entry: (SignedTransaction, Slot)): Boolean = entry._2.number < s.number

    for {
      all        <- pool.get.map(_.values)
      toBeEvicted = all.filter(expired).map(_._1).toList
      res        <- removeAll(toBeEvicted)
    } yield res
  }

  override def add(stx: SignedTransaction, slot: Slot): F[AddResult[SignedTransaction]] = {
    def addToPool(m: Map[K, V]): (Map[K, (SignedTransaction, Slot)], AddResult[SignedTransaction]) =
      if (m.size > config.transactionPoolSize)
        (m, NotAdded(stx, s"Transaction pool is full"))
      else {
        if (m.contains(stx.hash))
          (m, NotAdded(stx, "Transaction already in pool."))
        else
          (m.updated(stx.hash, (stx, slot)), Added(stx, slot))
      }

    for {
      result <- pool.modify(addToPool)
      _      <- pool.get.flatMap(p => executionMetrics.transactionPoolSize.set(p.size))
    } yield result
  }

  override def addAll[B[_]: Traverse](
      stxs: B[SignedTransaction],
      slot: Slot
  ): F[B[AddResult[SignedTransaction]]] =
    stxs.traverse(add(_, slot))

  override def remove(stx: SignedTransaction): F[RemovalResult[SignedTransaction]] = {
    def removeIfPresent(m: Map[K, V]) =
      if (m.contains(stx.hash)) {
        (m.removed(stx.hash), Removed(stx))
      } else {
        (m, NotRemoved(stx, "Not present."))
      }

    for {
      res <- pool.modify(removeIfPresent)
      _   <- pool.get.flatMap(p => executionMetrics.transactionPoolSize.set(p.size))
    } yield res
  }

  override def removeAll[B[_]: Traverse](stxs: B[SignedTransaction]): F[B[RemovalResult[SignedTransaction]]] =
    stxs.traverse(remove)

  override def getAll: F[List[SignedTransaction]] =
    pool.get.map(_.values.map(_._1).toList)

  private def calcLastSlotToKeep(currentSlot: Slot): F[Slot] =
    Sync[F]
      .delay((currentSlot.number - config.transactionTtlRounds + 1).max(0))
      .map(Slot.apply)
}

object SignedTransactionMempool {

  private type K = TransactionHash
  private type V = (SignedTransaction, Slot)

  def apply[F[_]: Async](config: MempoolConfig, executionMetrics: ExecutionMetrics[F]): F[SignedTransactionMempool[F]] =
    createWith(HashMap.empty, config, executionMetrics)

  private[mempool] def createWith[F[_]: Async](
      initial: Map[TransactionHash, (SignedTransaction, Slot)],
      config: MempoolConfig,
      executionMetrics: ExecutionMetrics[F]
  ): F[SignedTransactionMempool[F]] =
    for {
      pool <- Ref.of[F, Map[K, V]](initial)
    } yield new SignedTransactionMempool(pool, config, executionMetrics)

}
