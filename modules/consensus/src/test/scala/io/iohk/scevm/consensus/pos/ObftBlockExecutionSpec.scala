package io.iohk.scevm.consensus.pos

import cats.data.EitherT
import cats.effect.IO
import io.iohk.bytes.ByteString
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionError.{
  ErrorDuringExecution,
  ValidationAfterExecError
}
import io.iohk.scevm.consensus.pos.ObftBlockExecution.{BlockExecutionError, BlockExecutionResult}
import io.iohk.scevm.consensus.validators.PostExecutionValidator
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.EvmCodeStorageImpl
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.vm.{InMemoryWorldState, WorldType}
import io.iohk.scevm.ledger.BlockRewarder.BlockRewarder
import io.iohk.scevm.ledger.TransactionExecution
import io.iohk.scevm.ledger.TransactionExecution.{StateBeforeFailure, TxsExecutionError}
import io.iohk.scevm.metrics.instruments.{Counter, Histogram}
import io.iohk.scevm.mpt.EphemeralMptStorage
import io.iohk.scevm.testing.BlockGenerators.obftBlockGen
import io.iohk.scevm.testing.IOSupport
import io.iohk.scevm.utils.SystemTime.UnixTimestamp
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ObftBlockExecutionSpec extends AnyWordSpec with Matchers with MockFactory with IOSupport with ScalaFutures {

  "ObftBlockExecution" when {

    val worldState                                     = ObftBlockExecutionSpec.inMemoryWorldState
    val block: ObftBlock                               = obftBlockGen.sample.get
    val transactionExecution: TransactionExecution[IO] = (_, _, _, _) => ???
    val blockRewardCalculator: BlockRewarder[IO]       = _ => ???
    val sucessfulPostExecValidator = new PostExecutionValidator[IO] {
      override def validate(
          initialState: WorldType,
          header: ObftHeader,
          result: BlockExecutionResult
      ): IO[Either[PostExecutionValidator.PostExecutionError, Unit]] = IO.pure(Right(()))
    }

    class ObftBlockExecutionImplMocked(
        postExecutionValidator: PostExecutionValidator[IO] = sucessfulPostExecValidator,
        blockPreExecution: BlockPreExecution[IO] = BlockPreExecution.noop[IO]
    ) extends ObftBlockExecutionImpl[IO](
          blockPreExecution,
          transactionExecution,
          postExecutionValidator,
          blockRewardCalculator,
          Counter.noop
        ) {

      override def executeTransactionAndPayReward(
          state: InMemoryWorldState,
          block: ObftBlock
      ): EitherT[IO, BlockExecutionError, BlockExecutionResult] =
        EitherT.fromEither(Right(BlockExecutionResult(state)))
    }

    "executing block" should {

      "return execution result when the execution succeeds and post execution validation passes" in {
        val obftBlockExecutionImpl = new ObftBlockExecutionImplMocked(sucessfulPostExecValidator)

        val result = obftBlockExecutionImpl.executeBlock(block, worldState).ioValue
        result shouldBe Right(BlockExecutionResult(worldState))
      }

      "return a ValidationAfterExecError if the post execution validation fails" in {
        val error = PostExecutionValidator.PostExecutionError(block.hash, "test-error")
        val failingPostExecValidator = new PostExecutionValidator[IO] {
          override def validate(
              initialState: WorldType,
              header: ObftHeader,
              result: BlockExecutionResult
          ): IO[Either[PostExecutionValidator.PostExecutionError, Unit]] = IO.pure(Left(error))
        }
        val obftBlockExecutionImpl = new ObftBlockExecutionImplMocked(failingPostExecValidator)

        val result = obftBlockExecutionImpl.executeBlock(block, worldState).ioValue
        result shouldBe Left(ValidationAfterExecError(error))
      }

      "return an ErrorDuringExecution when the execution fails" in {
        val errorDuringExecution = ErrorDuringExecution(
          block.hash,
          TxsExecutionError(
            block.body.transactionList.head,
            StateBeforeFailure(worldState, 0, Seq.empty),
            "execution failed"
          )
        )
        val obftBlockExecutionImpl = new ObftBlockExecutionImplMocked() {
          override def executeTransactionAndPayReward(
              state: InMemoryWorldState,
              block: ObftBlock
          ): EitherT[IO, BlockExecutionError, BlockExecutionResult] =
            EitherT.fromEither(Left(errorDuringExecution))
        }

        val result = obftBlockExecutionImpl.executeBlock(block, worldState).ioValue
        result shouldBe Left(errorDuringExecution)
      }

      "should use the state provided by BlockPreExecution" in {
        val code = ByteString.fromString("fakeCode")

        val obftBlockExecutionImpl = new ObftBlockExecutionImplMocked(
          blockPreExecution = state => {
            IO.pure(state.saveCode(Address(0), code))
          }
        )

        val result = obftBlockExecutionImpl.executeBlock(block, worldState).ioValue
        result.toOption.get.worldState.getCode(Address(0)) shouldBe code
      }

    }
  }
}

object ObftBlockExecutionSpec {
  def inMemoryWorldState: InMemoryWorldState = InMemoryWorldState(
    new EvmCodeStorageImpl(
      EphemDataSource(),
      Histogram.noOpClientHistogram(),
      Histogram.noOpClientHistogram()
    ),
    new EphemeralMptStorage,
    (_, _) => None,
    Nonce(0),
    ByteString.empty,
    noEmptyAccounts = false,
    BlockContext(
      BlockHash(ByteString.empty),
      BlockNumber(0),
      Slot(1),
      BigInt(0),
      Address(ByteString.empty),
      UnixTimestamp(1)
    )
  )
}
