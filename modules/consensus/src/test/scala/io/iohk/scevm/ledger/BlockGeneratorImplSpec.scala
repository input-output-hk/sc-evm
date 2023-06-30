package io.iohk.scevm.ledger

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionResult
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.vm.InMemoryWorldState
import io.iohk.scevm.ledger.blockgeneration.BlockGeneratorImpl
import io.iohk.scevm.mpt.MptStorage
import io.iohk.scevm.testing.BlockGenerators.obftBlockGen
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class BlockGeneratorImplSpec extends AnyWordSpec with Matchers with MockFactory {

  "ObftBlockGenerator" when {
    "generating block" should {
      val parent: ObftBlock = obftBlockGen.sample.get

      val mockedInMemoryWorldState = mock[InMemoryWorldState]
      val preparedBlock = PreparedBlock(
        parent.body.transactionList,
        BlockExecutionResult(mockedInMemoryWorldState),
        parent.header.stateRoot,
        mockedInMemoryWorldState
      )

      val blockPreparation = new BlockPreparation[IO] {
        override def prepareBlock(
            blockContext: BlockContext,
            transactionList: Seq[SignedTransaction],
            parent: ObftHeader
        ): IO[PreparedBlock] = IO.pure(preparedBlock)

      }
      val prvKey                 = ECDSA.PrivateKey.fromHexUnsafe("f46bf49093d585f2ea781a0bf6d83468919f547999ad91c9210256979d88eea2")
      val pubKey                 = ECDSA.PublicKey.fromPrivateKey(prvKey)
      val obftBlockGeneratorImpl = new BlockGeneratorImpl(blockPreparation)

      "return a block when generation succeeds" in {
        val slot = Slot(6)

        val result =
          obftBlockGeneratorImpl
            .generateBlock(parent.header, parent.body.transactionList, slot, 1486131165.millisToTs, pubKey, prvKey)
            .unsafeRunSync()

        result.header.number shouldBe parent.header.number.next
        result.header.slotNumber shouldBe slot
        result.header.transactionsRoot shouldBe MptStorage.rootHash(
          parent.body.transactionList,
          SignedTransaction.byteArraySerializable
        )
        result.header.stateRoot shouldBe parent.header.stateRoot
      }
    }
  }
}
