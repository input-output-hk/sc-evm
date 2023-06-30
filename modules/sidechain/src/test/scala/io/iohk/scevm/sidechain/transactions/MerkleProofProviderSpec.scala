package io.iohk.scevm.sidechain.transactions

import akka.util.ByteString
import cats.data.{NonEmptyList, StateT}
import cats.effect.IO
import cats.syntax.all._
import io.iohk.scevm.domain.{Token, UInt256}
import io.iohk.scevm.exec.vm.{IOEvmCall, WorldType}
import io.iohk.scevm.sidechain.BridgeContract.MerkleRootEntry.NewMerkleRoot
import io.iohk.scevm.sidechain.EVMTestUtils.EVMFixture
import io.iohk.scevm.sidechain.SidechainEpoch
import io.iohk.scevm.sidechain.transactions.MerkleProofProvider.CombinedMerkleProofWithDetails
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.wordspec.AnyWordSpec

class MerkleProofProviderSpec
    extends AnyWordSpec
    with IOSupport
    with ScalaFutures
    with OptionValues
    with NormalPatience {
  val bridgeAccountStorage: Map[UInt256, UInt256] = {
    val currentTxsBatchMRHEpochMemorySlot = UInt256(3)
    Map(currentTxsBatchMRHEpochMemorySlot -> UInt256.MaxValue)
  }

  val fixture: EVMFixture = EVMFixture()

  "when there is an outgoing transactions then there should be a proof for that epoch and the proof should be correct" in {
    val transactions =
      NonEmptyList.of(OutgoingTransaction(Token(123), OutgoingTxRecipient(ByteString(1)), OutgoingTxId(0)))
    val tree = TransactionsMerkleTree(None, transactions)

    val service =
      new MerkleProofProviderImpl[IO](
        new BridgeContractStub(
          Map(UInt256(1)        -> transactions.toList),
          Map(SidechainEpoch(1) -> NewMerkleRoot(tree.rootHash, None))
        )
      )
    val program = StateT.inspectF[IO, WorldType, Option[CombinedMerkleProofWithDetails]](world =>
      service.getProof(world)(SidechainEpoch(1), OutgoingTxId(0))
    )

    val result = fixture.run(program).ioValue.value
    assert(TransactionsMerkleTree.verifyProof(tree.rootHash)(result.combined))
  }

  "when there is an outgoing transactions then there should be a proof for that epoch and the proof should be correct - including previous epoch entry" in {
    val transactionsEpoch1 =
      NonEmptyList.of(OutgoingTransaction(Token(123), OutgoingTxRecipient(ByteString(1)), OutgoingTxId(0)))
    val transactionsEpoch2 =
      NonEmptyList.of(OutgoingTransaction(Token(456), OutgoingTxRecipient(ByteString(2)), OutgoingTxId(0)))
    val treeEpoch1 = TransactionsMerkleTree(None, transactionsEpoch1)
    val treeEpoch2 = TransactionsMerkleTree(Some(treeEpoch1.rootHash), transactionsEpoch2)

    val service =
      new MerkleProofProviderImpl[IO](
        new BridgeContractStub(
          Map(UInt256(1) -> transactionsEpoch1.toList, UInt256(2) -> transactionsEpoch2.toList),
          Map(
            SidechainEpoch(1) -> NewMerkleRoot(treeEpoch1.rootHash, None),
            SidechainEpoch(2) -> NewMerkleRoot(treeEpoch2.rootHash, Some(SidechainEpoch(1)))
          )
        )
      )
    val program = StateT.inspectF[IO, WorldType, Option[CombinedMerkleProofWithDetails]](world =>
      service.getProof(world)(SidechainEpoch(2), OutgoingTxId(0))
    )

    val result = fixture.run(program).ioValue.value
    assert(TransactionsMerkleTree.verifyProof(treeEpoch2.rootHash)(result.combined))
  }

  "when there are no outgoing transactions then there should be no proof" in {
    val service = new MerkleProofProviderImpl[IO](new BridgeContractStub(Map(), Map()))
    val program = StateT.inspectF[IO, WorldType, Option[CombinedMerkleProofWithDetails]](world =>
      service.getProof(world)(SidechainEpoch(0), OutgoingTxId(0))
    )

    assert(fixture.run(program).ioValue.isEmpty)
  }

  "should calculate proof for multiple transactions" in {
    val transactions = NonEmptyList.of(
      OutgoingTransaction(Token(123), OutgoingTxRecipient(ByteString(1)), OutgoingTxId(0)),
      OutgoingTransaction(Token(124), OutgoingTxRecipient(ByteString(2)), OutgoingTxId(1)),
      OutgoingTransaction(Token(123), OutgoingTxRecipient(ByteString(1)), OutgoingTxId(2))
    )
    val tree = TransactionsMerkleTree(None, transactions)

    val service =
      new MerkleProofProviderImpl[IO](
        new BridgeContractStub(
          Map(UInt256(1)        -> transactions.toList),
          Map(SidechainEpoch(1) -> NewMerkleRoot(tree.rootHash, None))
        )
      )

    transactions.toList.foreach { tx =>
      val program = StateT.inspectF[IO, WorldType, Option[CombinedMerkleProofWithDetails]](world =>
        service.getProof(world)(SidechainEpoch(1), tx.txIndex)
      )
      val result = fixture.run(program).ioValue.value
      assert(TransactionsMerkleTree.verifyProof(tree.rootHash)(result.combined))
    }
  }

  "should not return proof for an invalid txId" in {
    val transactions =
      NonEmptyList.of(OutgoingTransaction(Token(123), OutgoingTxRecipient(ByteString(1)), OutgoingTxId(0)))
    val tree = TransactionsMerkleTree(None, transactions)

    val service =
      new MerkleProofProviderImpl[IO](
        new BridgeContractStub(
          Map(UInt256(1)        -> transactions.toList),
          Map(SidechainEpoch(1) -> NewMerkleRoot(tree.rootHash, None))
        )
      )
    val program = StateT.inspectF[IO, WorldType, Option[CombinedMerkleProofWithDetails]](world =>
      service.getProof(world)(SidechainEpoch(1), OutgoingTxId(1))
    )

    assert(fixture.run(program).ioValue.isEmpty)
  }

  "should fail when there is no entry for previous merkle root but there should be" in {
    val transactions =
      NonEmptyList.of(OutgoingTransaction(Token(123), OutgoingTxRecipient(ByteString(1)), OutgoingTxId(0)))
    val tree = TransactionsMerkleTree(None, transactions)

    val service =
      new MerkleProofProviderImpl[IO](
        new BridgeContractStub(
          Map(UInt256(1)        -> transactions.toList),
          Map(SidechainEpoch(1) -> NewMerkleRoot(tree.rootHash, Some(SidechainEpoch(0))))
        )
      )
    val program = StateT.inspectF[IO, WorldType, Option[CombinedMerkleProofWithDetails]](world =>
      service.getProof(world)(SidechainEpoch(1), OutgoingTxId(0))
    )

    fixture.run(program).attempt.ioValue match {
      case Left(_)  => succeed
      case Right(_) => fail("Expected to fail, but succeed")
    }
  }

  class BridgeContractStub(
      transactionsBatches: Map[UInt256, Seq[OutgoingTransaction]],
      rootChain: Map[SidechainEpoch, NewMerkleRoot]
  ) extends MerkleProofProvider.BridgeContract[IOEvmCall] {
    override def getTransactionsInBatch(
        batch: UInt256
    ): IOEvmCall[Seq[OutgoingTransaction]] = transactionsBatches.getOrElse(batch, Seq.empty).pure[IOEvmCall]

    override def getMerkleRootChainEntry(
        epoch: SidechainEpoch
    ): IOEvmCall[Option[NewMerkleRoot]] = rootChain.get(epoch).pure[IOEvmCall]

  }
}
