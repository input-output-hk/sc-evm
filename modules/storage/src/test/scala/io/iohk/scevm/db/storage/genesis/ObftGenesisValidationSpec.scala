package io.iohk.scevm.db.storage.genesis

import cats.effect.IO
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.{BlockchainStorage, BlocksReader, BlocksWriter, ReceiptStorage}
import io.iohk.scevm.domain.{BlockHash, BlockNumber, ObftBlock, ObftBody, ObftHeader, Receipt}
import io.iohk.scevm.testing.BlockGenerators
import org.scalatest.Assertions
import org.scalatest.wordspec.AsyncWordSpec

class ObftGenesisValidationSpec extends AsyncWordSpec {
  import cats.effect.unsafe.implicits.global

  private def error[T]: IO[T] = IO.raiseError[T](new RuntimeException("Unexpected call during test"))

  private val genesis = BlockGenerators.obftBlockGen.sample.get
  private val hashByNumber: BlockNumber => IO[Option[BlockHash]] =
    n => if (n == BlockNumber(0)) IO.pure(Option(genesis.hash)) else error

  " ObftGenesisValidation" when {
    "there is a stored genesis" should {
      val test: ObftBlock => IO[Unit] =
        ObftGenesisValidation.initGenesis[IO](
          hashByNumber = hashByNumber,
          blocksStorageReader = new BlocksReader[IO] {
            override def getBlockHeader(hash: BlockHash): IO[Option[ObftHeader]] = error
            override def getBlockBody(hash: BlockHash): IO[Option[ObftBody]]     = error
            override def getBlock(hash: BlockHash): IO[Option[ObftBlock]] =
              if (hash == genesis.hash) IO.pure(Some(genesis))
              else error
          },
          blocksWriter = new BlocksWriter[IO] {
            def insertHeader(header: ObftHeader): IO[Unit]                 = IO.unit
            def insertBody(blockHash: BlockHash, body: ObftBody): IO[Unit] = IO.unit
          },
          receiptStorage = new ReceiptStorage[IO] {
            override def getReceiptsByHash(hash: BlockHash): IO[Option[Seq[Receipt]]]   = error
            override def putReceipts(hash: BlockHash, receipts: Seq[Receipt]): IO[Unit] = error
          }
        )

      "pass if genesis is the same as the one in storage" in {
        test(genesis)
          .map(_ => Assertions.succeed)
          .handleError(_ => Assertions.fail("This test should not throw"))
          .unsafeToFuture()
      }

      "fail if genesis is other than the one in storage" in {
        test(genesis.copy(header = genesis.header.copy(number = genesis.number.next)))
          .map(_ => Assertions.fail("This test should throw"))
          .handleError(_ => Assertions.succeed)
          .unsafeToFuture()
      }
    }

    "there is no stored genesis" should {

      "store genesis block and receipt in storage" in {
        val blockchainStorage = BlockchainStorage.unsafeCreate[IO](EphemDataSource())
        val receiptStorage    = ReceiptStorage.unsafeCreate[IO](EphemDataSource())

        val test: ObftBlock => IO[Unit] =
          ObftGenesisValidation.initGenesis[IO](hashByNumber, blockchainStorage, blockchainStorage, receiptStorage)

        test(genesis)
          .flatMap(_ =>
            for {
              blockIsAbsent   <- blockchainStorage.getBlock(genesis.hash).map(_.isEmpty)
              _               <- IO.raiseWhen(blockIsAbsent)(new RuntimeException("block should have been stored"))
              receiptIsAbsent <- receiptStorage.getReceiptsByHash(genesis.hash).map(_.isEmpty)
              _               <- IO.raiseWhen(receiptIsAbsent)(new RuntimeException("receipt should have been stored"))
            } yield Assertions.succeed
          )
          .handleError(error => Assertions.fail(error.getMessage))
          .unsafeToFuture()
      }
    }

  }

}
