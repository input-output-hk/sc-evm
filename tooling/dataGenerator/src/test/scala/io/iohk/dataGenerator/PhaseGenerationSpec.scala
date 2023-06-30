package io.iohk.dataGenerator

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.iohk.dataGenerator.domain.{ContractCall, FundedAccount, Validator}
import io.iohk.dataGenerator.fixtures.{NoOpBlockPreparation, ValidBlock}
import io.iohk.dataGenerator.services.PhaseGeneration
import io.iohk.dataGenerator.services.transactions.TransactionGenerator
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{ObftHeader, SignedTransaction}
import io.iohk.scevm.ledger.RoundRobinLeaderElection
import io.iohk.scevm.ledger.blockgeneration.BlockGeneratorImpl
import io.iohk.scevm.network.domain.ChainDensity
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.DurationInt

class PhaseGenerationSpec extends AnyFlatSpec with Matchers {
  private val slotDuration = 3.seconds
  private val validators = IndexedSeq(
    {
      val privateKey =
        ECDSA.PrivateKey.fromHexUnsafe("7a44789ed3cd85861c0bbf9693c7e1de1862dd4396c390147ecf1275099c6e6f")
      Validator(ECDSA.PublicKey.fromPrivateKey(privateKey), privateKey)
    }, {
      val privateKey =
        ECDSA.PrivateKey.fromHexUnsafe("bfbee74ab533f40979101057f96de62e95233f2a5216eb16b54106f09fd7350d")
      Validator(ECDSA.PublicKey.fromPrivateKey(privateKey), privateKey)
    }, {
      val privateKey =
        ECDSA.PrivateKey.fromHexUnsafe("cfbee74ab533f40979101057f96de62e95233f2a5216eb16b54106f09fd7350d")
      Validator(ECDSA.PublicKey.fromPrivateKey(privateKey), privateKey)
    }
  )

  private val phaseGeneration = new PhaseGeneration(
    new BlockGeneratorImpl[IO](NoOpBlockPreparation),
    new RoundRobinLeaderElection[IO](validators.map(_.publicKey)),
    new TransactionGenerator {
      override def chainId: ChainId = ChainId.unsafeFrom(1)

      override def transactions(
          parent: ObftHeader,
          contracts: Seq[ContractCall],
          senders: Seq[FundedAccount]
      ): IO[Seq[SignedTransaction]] =
        IO.pure(Seq.empty)

      override def deployContracts(contracts: Seq[ContractCall], senders: Seq[FundedAccount]): Seq[SignedTransaction] =
        Seq.empty

      override def callContracts(
          parent: ObftHeader,
          contracts: Seq[ContractCall],
          senders: Seq[FundedAccount],
          salt: Option[String]
      ): IO[Seq[SignedTransaction]] =
        IO.pure(Seq.empty)
    }
  )(slotDuration)

  "ChainGenerator" should "generate an empty chain if length is 0" in {
    val chainLength   = 0
    val startingBlock = ValidBlock.block
    val stream        = phaseGeneration.run(startingBlock, chainLength, validators)
    val blocks        = stream.compile.toVector.unsafeRunSync()
    blocks shouldBe empty
  }

  it should "generate a chain of density 1.0 if all validators are active" in {
    val chainLength   = 10
    val startingBlock = ValidBlock.block
    val stream        = phaseGeneration.run(startingBlock, chainLength, validators)
    val blocks        = stream.compile.toVector.unsafeRunSync()

    // Test
    blocks shouldNot contain(startingBlock)
    blocks.size shouldBe chainLength
    blocks.map(_.number.value).sorted shouldBe Range.inclusive(
      startingBlock.number.value.toInt + 1,
      startingBlock.number.value.toInt + chainLength
    )
    blocks.last.header.slotNumber shouldBe startingBlock.header.slotNumber.add(chainLength)
    ChainDensity.density(blocks.last.header, blocks.head.header) shouldBe 1.0
  }

  it should "generate a chain of density 2/3" in {
    val chainLength          = 10
    val two_third_validators = IndexedSeq(validators(0), validators(2))

    val startingBlock = ValidBlock.block
    val stream        = phaseGeneration.run(startingBlock, chainLength, two_third_validators)
    val blocks        = stream.compile.toVector.unsafeRunSync()

    blocks shouldNot contain(startingBlock)
    blocks.size shouldBe 7 // "2/3 of 10"
    blocks.map(_.number.value).sorted shouldBe Range.inclusive(
      startingBlock.number.value.toInt + 1,
      startingBlock.number.value.toInt + 7
    )
    blocks.last.header.slotNumber shouldBe startingBlock.header.slotNumber.add(chainLength)
    ChainDensity.density(blocks.last.header, blocks.head.header) shouldBe 2.toDouble / 3
  }

}
