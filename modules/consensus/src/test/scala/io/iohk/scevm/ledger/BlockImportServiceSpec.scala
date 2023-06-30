package io.iohk.scevm.ledger

import cats.Applicative
import cats.effect.IO
import io.iohk.scevm.consensus.validators.HeaderValidator
import io.iohk.scevm.db.storage.{BlocksReader, BlocksWriter}
import io.iohk.scevm.domain.{ObftBlock, ObftHeader, Slot}
import io.iohk.scevm.ledger.BlockImportService.{
  BlockWithValidatedHeader,
  ImportedBlock,
  UnvalidatedBlock,
  ValidatedBlock
}
import io.iohk.scevm.testing.BlockGenerators.obftBlockGen
import io.iohk.scevm.testing.{CryptoGenerators, IOSupport, NormalPatience}
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class BlockImportServiceSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with MockFactory
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  val blocksReader: BlocksReader[IO] = mock[BlocksReader[IO]]
  val blocksWriter: BlocksWriter[IO] = mock[BlocksWriter[IO]]

  val successfulPreImportValidator: HeaderValidator[IO] = (header: ObftHeader) => IO.pure(Right(header))

  val failingPreImportValidator: HeaderValidator[IO] = (header: ObftHeader) =>
    IO.pure(Left(HeaderValidator.HeaderInvalidSignature(header)))

  val leaderElection: LeaderElection[IO] = (_: Slot) => IO.pure(Right(CryptoGenerators.ecdsaKeyPairGen.sample.get._2))

  "BlockImportService" when {

    "importAndValidateBlock is called" should {

      "successfully import a block when given it is valid" in forAll(obftBlockGen) { block =>
        val tested = new BlockImportServiceImpl(blocksReader, blocksWriter, successfulPreImportValidator)

        (blocksReader.getBlock _).expects(*).once().returning(IO(None))
        (blocksWriter
          .insertBlock(_: ObftBlock)(_: Applicative[IO]))
          .expects(block, *)
          .once()
          .returning(IO.unit)

        val res = tested.importAndValidateBlock(UnvalidatedBlock(block)).ioValue
        res shouldBe Some(ImportedBlock(block, fullyValidated = false))
      }

      "not import the block when it is already saved in the blockchain" in forAll(obftBlockGen) { block =>
        val tested = new BlockImportServiceImpl(blocksReader, blocksWriter, successfulPreImportValidator)

        (blocksReader.getBlock _).expects(*).returning(IO(Some(block)))
        (blocksWriter.insertBlock(_: ObftBlock)(_: Applicative[IO])).expects(block, *).never()

        val res = tested.importAndValidateBlock(UnvalidatedBlock(block)).ioValue
        res shouldBe None
      }

      "not import the block when invalid" in forAll(obftBlockGen) { block =>
        val tested = new BlockImportServiceImpl(blocksReader, blocksWriter, failingPreImportValidator)

        (blocksReader.getBlock _).expects(*).never()
        (blocksWriter.insertBlock(_: ObftBlock)(_: Applicative[IO])).expects(*, *).never()

        val res = tested.importAndValidateBlock(UnvalidatedBlock(block)).ioValue
        res shouldBe None
      }
    }

    "importBlock is called" should {

      "import block when processing BlockWithValidatedHeader" in forAll(
        obftBlockGen
      ) { block =>
        val tested = new BlockImportServiceImpl(blocksReader, blocksWriter, failingPreImportValidator)

        (blocksReader.getBlock _).expects(*).once().returning(IO(None))
        (blocksWriter
          .insertBlock(_: ObftBlock)(_: Applicative[IO]))
          .expects(block, *)
          .once()
          .returning(IO.unit)

        val res = tested.importBlock(BlockWithValidatedHeader(block)).ioValue
        res shouldBe Some(ImportedBlock(block, fullyValidated = false))
      }

      "import block when processing ValidatedBlock" in forAll(
        obftBlockGen
      ) { block =>
        val tested = new BlockImportServiceImpl(blocksReader, blocksWriter, failingPreImportValidator)

        (blocksReader.getBlock _).expects(*).once().returning(IO(None))
        (blocksWriter
          .insertBlock(_: ObftBlock)(_: Applicative[IO]))
          .expects(block, *)
          .once()
          .returning(IO.unit)

        val res = tested.importBlock(ValidatedBlock(block)).ioValue
        res shouldBe Some(ImportedBlock(block, fullyValidated = true))
      }
    }
  }
}
