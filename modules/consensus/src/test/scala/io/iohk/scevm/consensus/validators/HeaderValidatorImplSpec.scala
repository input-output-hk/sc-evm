package io.iohk.scevm.consensus.validators

import cats.Id
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.domain.{Address, ObftHeader, Slot}
import io.iohk.scevm.ledger.{LeaderElection, RoundRobinLeaderElection, SlotDerivation}
import io.iohk.scevm.testing.BlockGenerators.obftBlockHeaderGen
import io.iohk.scevm.testing.{Generators, fixtures}
import org.scalacheck.Gen
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

import java.security.SecureRandom

import HeaderValidator._

class HeaderValidatorImplSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with EitherValues
    with MockFactory {
  implicit private val loggerFactory: LoggerFactory[Id] = NoOpFactory[Id]

  val (prvKey, pubKey)               = ECDSA.generateKeyPair(new SecureRandom())
  val leaderElection                 = new RoundRobinLeaderElection[Id](IndexedSeq.empty)
  val genesisHeader: ObftHeader      = fixtures.GenesisBlock.header
  val slotDerivation: SlotDerivation = mock[SlotDerivation]

  "validate" should {
    "approve of the preset genesis" in {
      val validator = new HeaderValidatorImpl(leaderElection, genesisHeader, slotDerivation)
      validator.validate(genesisHeader) shouldEqual Right(genesisHeader)
    }

    "disapprove of a fake genesis" in {
      val validator   = new HeaderValidatorImpl(leaderElection, genesisHeader, slotDerivation)
      val fakeGenesis = genesisHeader.copy(beneficiary = Address(ByteString("3v1l_h4ck3r")))
      validator.validate(fakeGenesis) shouldEqual Left(HeaderValidator.HeaderDifferentGenesis(fakeGenesis))
    }

    "disapprove if can't get slot leader" in {
      val header                             = fixtures.ValidBlock.header
      val leaderElection: LeaderElection[Id] = (_: Slot) => Left(LeaderElection.ElectionFailure.notEnoughCandidates)
      val validator                          = new HeaderValidatorImpl(leaderElection, genesisHeader, slotDerivation)
      validator.validate(header) shouldEqual Left(HeaderNotLeader(header))
    }

    "disapprove if public signing key isn't slot leader" in {
      val header           = fixtures.ValidBlock.header
      val (_, otherPubKey) = ECDSA.generateKeyPair(new SecureRandom())
      val leaderElection   = new RoundRobinLeaderElection[Id](IndexedSeq(otherPubKey))
      val validator        = new HeaderValidatorImpl(leaderElection, genesisHeader, slotDerivation)
      validator.validate(header) shouldEqual Left(HeaderNotLeader(header))
    }

    "disapprove if beneficiary isn't slot leader" in {
      val header         = fixtures.ValidBlock.header.copy(publicSigningKey = pubKey)
      val leaderElection = new RoundRobinLeaderElection[Id](IndexedSeq(pubKey))
      val validator      = new HeaderValidatorImpl(leaderElection, genesisHeader, slotDerivation)
      validator.validate(header) shouldEqual Left(BeneficiaryNotLeader(header))
    }

  }

  "validateGasLimit" when {
    "the block is valid" should {
      "pass" in {
        forAll(obftBlockHeaderGen) { header =>
          lazy val validator = new HeaderValidatorImpl(leaderElection, genesisHeader, slotDerivation)
          validator.validateGasLimit(header) shouldBe Right(header)
        }
      }
    }

    "the limit is negative" should {
      "fail" in {
        forAll(obftBlockHeaderGen, Gen.choose(Int.MinValue, -1)) { (header, negativeLimit) =>
          lazy val validator = new HeaderValidatorImpl(leaderElection, genesisHeader, slotDerivation)
          val modifiedHeader = header.copy(gasLimit = negativeLimit)
          validator.validateGasLimit(modifiedHeader) shouldBe Left(GasLimitError(modifiedHeader))
        }
      }
    }
  }

  "validateGasUsed" when {
    "the block is valid" should {
      "pass" in {
        forAll(obftBlockHeaderGen) { header =>
          lazy val validator = new HeaderValidatorImpl(leaderElection, genesisHeader, slotDerivation)
          validator.validateGasUsed(header) shouldBe Right(header)
        }
      }
    }

    "the gas used is negative" should {
      "fail" in {
        forAll(obftBlockHeaderGen, Gen.choose(Int.MinValue, -1)) { (header, used) =>
          lazy val validator = new HeaderValidatorImpl(leaderElection, genesisHeader, slotDerivation)
          val modifiedHeader = header.copy(gasUsed = used)
          validator.validateGasUsed(modifiedHeader) shouldBe Left(GasUsedError(modifiedHeader))
        }
      }
    }

    "the gas has been overspent" should {
      "fail" in {
        forAll(obftBlockHeaderGen, Gen.choose(0, Int.MaxValue)) { (header, used) =>
          lazy val validator = new HeaderValidatorImpl(leaderElection, genesisHeader, slotDerivation)
          val modifiedHeader = header.copy(gasUsed = header.gasLimit + used)
          validator.validateGasUsed(modifiedHeader) shouldBe Left(GasUsedError(modifiedHeader))
        }
      }
    }
  }

  "validateMaxGasLimit" when {
    "the block is valid" should {
      "pass" in {
        forAll(obftBlockHeaderGen) { header =>
          lazy val validator = new HeaderValidatorImpl(leaderElection, genesisHeader, slotDerivation)
          validator.validateMaxGasLimit(header, Long.MaxValue) shouldBe Right(header)
        }
      }
    }

    "the limit is more than maxGasLimit" should {
      "fail" in {
        forAll(obftBlockHeaderGen, Generators.intGen(1, Integer.MAX_VALUE)) { (header, biggerGas) =>
          lazy val validator = new HeaderValidatorImpl(leaderElection, genesisHeader, slotDerivation)
          val modifiedHeader = header.copy(gasLimit = biggerGas)
          val maxGasLimit    = 0
          validator.validateMaxGasLimit(modifiedHeader, maxGasLimit) shouldBe Left(MaxGasLimitError(modifiedHeader))
        }
      }
    }
  }

  "validateSlotAgainstTimestamp" when {
    lazy val validator = new HeaderValidatorImpl(leaderElection, genesisHeader, slotDerivation)

    "the slotNumber is correct" should {
      "pass" in {
        forAll(obftBlockHeaderGen) { header =>
          validator.validateSlotAgainstTimestamp(_ => Right(header.slotNumber))(header) shouldBe Right(header)
        }
      }
    }

    "the slotNumber is invalid" should {
      "fail" in {
        forAll(obftBlockHeaderGen) { header =>
          validator.validateSlotAgainstTimestamp(_ => Right(Slot(header.slotNumber.number + 1)))(header) shouldBe Left(
            SlotNumberError(header)
          )
        }
      }
    }

    "the timestamp is not valid" should {
      "fail" in {
        forAll(obftBlockHeaderGen) { header =>
          validator.validateSlotAgainstTimestamp(_ => Left(NoTimestampError(header)))(header) shouldBe Left(
            NoTimestampError(header)
          )
        }
      }
    }
  }
}
