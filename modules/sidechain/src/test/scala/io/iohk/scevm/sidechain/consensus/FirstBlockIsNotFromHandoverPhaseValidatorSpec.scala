package io.iohk.scevm.sidechain.consensus

import cats.effect.IO
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionResult
import io.iohk.scevm.domain.{BlockNumber, EpochPhase, ObftHeader, Slot}
import io.iohk.scevm.exec.utils.TestInMemoryWorldState
import io.iohk.scevm.exec.vm.WorldType
import io.iohk.scevm.sidechain
import io.iohk.scevm.sidechain.SidechainEpochDerivation
import io.iohk.scevm.testing.Generators.blockNumberGen
import io.iohk.scevm.testing.{BlockGenerators, IOSupport, NormalPatience}
import io.iohk.scevm.utils.SystemTime
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class FirstBlockIsNotFromHandoverPhaseValidatorSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with EitherValues
    with IOSupport
    with NormalPatience {

  import FirstBlockIsNotFromHandoverPhaseValidatorSpec._

  "invalidates block" when {
    "block number is 1 and epoch phase is Handover" in {
      val validator = makeValidator(EpochPhase.Handover)
      val result    = validator.validate(world, header(BlockNumber(1)), blockExecutionResult).ioValue
      (result.left.value.message should fullyMatch).regex("Block BlockTag\\(number=1.*\\) is from Handover phase")
    }
  }

  "validates block" when {
    "block number is 1 and epoch phase is not Handover" in {
      val phases = Table("epochPhase", EpochPhase.Regular, EpochPhase.ClosedTransactionBatch)
      forAll(phases) { phase =>
        val validator = makeValidator(phase)
        val result    = validator.validate(world, header(BlockNumber(1)), blockExecutionResult).ioValue
        result.value shouldBe ()
      }
    }

    "block number is not 1 and epoch phase is Handover" in {
      forAll(blockNumberGen.suchThat(_ != BlockNumber(1))) { blockNumber =>
        val validator = makeValidator(EpochPhase.Handover)
        val result    = validator.validate(world, header(blockNumber), blockExecutionResult).ioValue
        result.value shouldBe ()
      }
    }
  }
}

object FirstBlockIsNotFromHandoverPhaseValidatorSpec {
  val world: WorldType   = TestInMemoryWorldState.worldStateGen.sample.get
  val expectedSlot: Slot = Slot(342)
  def header(number: BlockNumber): ObftHeader =
    BlockGenerators.obftBlockHeaderGen.sample.get.copy(number = number, slotNumber = expectedSlot)
  val blockExecutionResult: BlockExecutionResult = BlockExecutionResult(world)

  def makeValidator(epochPhase: EpochPhase) =
    new FirstBlockIsNotFromHandoverPhaseValidator[IO](
      new SidechainEpochDerivation[IO] {
        override def getCurrentEpoch: IO[sidechain.SidechainEpoch] = ???

        override def getSidechainEpoch(slot: Slot): sidechain.SidechainEpoch = ???

        override def getSidechainEpoch(time: SystemTime.UnixTimestamp): Option[sidechain.SidechainEpoch] = ???

        override def getSidechainEpochStartTime(epoch: sidechain.SidechainEpoch): SystemTime.TimestampSeconds = ???

        override def getSidechainEpochPhase(slot: Slot): EpochPhase =
          if (slot == expectedSlot) epochPhase
          else throw new Exception("unexpected invocation in test, slot should be taken from block header")
      }
    )
}
