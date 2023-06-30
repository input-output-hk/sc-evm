package io.iohk.scevm.consensus.validators

import cats.effect.{IO, Ref}
import cats.implicits.catsSyntaxEitherId
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionResult
import io.iohk.scevm.consensus.validators.PostExecutionValidator.PostExecutionError
import io.iohk.scevm.domain.ObftHeader
import io.iohk.scevm.exec.utils.TestInMemoryWorldState
import io.iohk.scevm.exec.vm.WorldType
import io.iohk.scevm.testing.{IOSupport, NormalPatience, fixtures}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CompositePostExecutionValidatorSpec
    extends AnyWordSpec
    with ScalaFutures
    with IOSupport
    with Matchers
    with NormalPatience {

  val header: ObftHeader = fixtures.ValidBlock.header

  val initialState: WorldType          = TestInMemoryWorldState.worldStateGen.sample.get
  val stateAfter: WorldType            = TestInMemoryWorldState.worldStateGen.sample.get
  val execResult: BlockExecutionResult = BlockExecutionResult(stateAfter, 333, Seq.empty)

  val parametersCheckingValidator: PostExecutionValidator[IO] = (s, h, r) =>
    if (s == initialState && h == header && r == execResult) IO.pure(Right(()))
    else IO.raiseError(new Exception("unexpected parameter"))

  val expectedError: PostExecutionError = PostExecutionError(header.hash, "expected failure")
  val failingValidator: PostExecutionValidator[IO] = (s, h, r) =>
    if (s == initialState && h == header && r == execResult) IO.pure(Left(expectedError))
    else IO.raiseError(new Exception("unexpected parameter"))

  def mockValidator(flag: Ref[IO, Boolean]): PostExecutionValidator[IO] = (_, _, _) => flag.set(true).as(().asRight)

  "fails validation" when {
    "any validator fails" in {
      val validators = List(failingValidator, parametersCheckingValidator, parametersCheckingValidator)
      validators.permutations.foreach { validators =>
        val validator = new CompositePostExecutionValidator[IO](validators)
        validator.validate(initialState, header, execResult).ioValue shouldBe Left(expectedError)
      }
    }

    "short circuits on validator error" in {
      (for {
        flag      <- IO.ref(false)
        validator  = new CompositePostExecutionValidator[IO](List(failingValidator, mockValidator(flag)))
        result    <- validator.validate(initialState, header, execResult)
        flagValue <- flag.get
      } yield {
        result shouldBe Left(expectedError)
        flagValue shouldBe false
      }).ioValue
    }
  }

  "passes validation" when {
    "base all validators passes" in {
      val validator =
        new CompositePostExecutionValidator[IO](List(parametersCheckingValidator, parametersCheckingValidator))
      validator.validate(initialState, header, execResult).ioValue shouldBe Right(())
    }

    "it composes no validators" in {
      val validator = new CompositePostExecutionValidator[IO](List.empty)
      validator.validate(initialState, header, execResult).ioValue shouldBe Right(())
    }
  }
}
