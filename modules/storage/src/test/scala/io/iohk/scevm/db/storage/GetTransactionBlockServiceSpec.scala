package io.iohk.scevm.db.storage

import cats.effect.IO
import cats.implicits.toTraverseOps
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.domain.{ObftBlock, TransactionHash, TransactionLocation}
import io.iohk.scevm.testing.BlockGenerators.obftChainBlockWithTransactionsGen
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class GetTransactionBlockServiceSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  "getTransactionBlockHash" should {
    "successfully find a transaction location in unstable part" in forAllBlockchainWithTransaction(
      withTransactionInUnstable = true
    ) { (chain, stableBlock, transactionHash, expectedTransactionLocation) =>
      val blockchainStorage         = BlockchainStorage.unsafeCreate[IO](EphemDataSource())
      val transactionMappingStorage = new StableTransactionMappingStorageImpl[IO](EphemDataSource())
      val appStorage                = ObftAppStorage.unsafeCreate[IO](EphemDataSource())

      val transactionBlockService = new GetTransactionBlockServiceImpl[IO](
        blockchainStorage,
        transactionMappingStorage
      )

      (for {
        _ <- chain.traverse(block => blockchainStorage.insertBlock(block))
        _ <- appStorage.putLatestStableBlockHash(stableBlock.hash)
        blockHash <-
          transactionBlockService.getTransactionLocation(stableBlock.header, chain.last.hash)(transactionHash)
      } yield blockHash shouldBe Some(expectedTransactionLocation)).ioValue
    }

    "successfully find a transaction location in stable" in forAllBlockchainWithTransaction(withTransactionInUnstable =
      false
    ) { (chain, stableBlock, transactionHash, expectedTransactionLocation) =>
      val blockchainStorage         = BlockchainStorage.unsafeCreate[IO](EphemDataSource())
      val transactionMappingStorage = new StableTransactionMappingStorageImpl[IO](EphemDataSource())
      val appStorage                = ObftAppStorage.unsafeCreate[IO](EphemDataSource())

      val transactionBlockService = new GetTransactionBlockServiceImpl[IO](
        blockchainStorage,
        transactionMappingStorage
      )

      (for {
        _ <- IO( // put the transaction into the storage as all the stable transaction should be indexed
               transactionMappingStorage
                 .put(transactionHash, expectedTransactionLocation)
                 .commit()
             )
        _ <- appStorage.putLatestStableBlockHash(stableBlock.hash)
        blockHash <-
          transactionBlockService.getTransactionLocation(stableBlock.header, chain.last.hash)(transactionHash)
      } yield blockHash shouldBe Some(expectedTransactionLocation)).ioValue
    }
  }

  val unstablePartGen: Gen[Int] = Gen.choose(6, 9)
  val stablePartGen: Gen[Int]   = Gen.choose(0, 5)
  private def forAllBlockchainWithTransaction(withTransactionInUnstable: Boolean)(
      test: (Seq[ObftBlock], ObftBlock, TransactionHash, TransactionLocation) => Unit
  ) =
    forAll(
      obftChainBlockWithTransactionsGen(minSize = 10, maxSize = 10),
      if (withTransactionInUnstable) unstablePartGen
      else stablePartGen, // block where we should include the transaction
      Gen.choose(0, 9)    // index of the transaction inside the block
    ) { (chain, transactionBlockIndex, transactionIndex) =>
      val stableIndex = 5

      val bodyWithTargetTransaction = chain(transactionBlockIndex).body
      val realTransactionIndex      = Math.min(transactionIndex, bodyWithTargetTransaction.transactionList.size)
      val transaction = bodyWithTargetTransaction.transactionList
        .lift(transactionIndex)
        .getOrElse(bodyWithTargetTransaction.transactionList.last)

      val stableBlock = chain(stableIndex)

      test(
        chain,
        stableBlock,
        transaction.hash,
        TransactionLocation(chain(transactionBlockIndex).hash, realTransactionIndex)
      )
    }

}
