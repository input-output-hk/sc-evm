package io.iohk.scevm.sidechain

import com.typesafe.config.ConfigFactory
import io.iohk.scevm.trustlesssidechain.cardano.Lovelace
import org.scalactic.source.Position
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.{Failure, Success, Try}

class SidechainBlockchainConfigSpec extends AnyWordSpec with Matchers {

  def resultForConfig(configFile: String): Try[SidechainBlockchainConfig] = Try(
    SidechainBlockchainConfig.fromRawConfig(ConfigFactory.load(configFile))
  )

  def expectFailure(configFile: String, errorMessage: String)(implicit pos: Position): Assertion =
    resultForConfig(configFile) match {
      case Failure(exception) => exception.getMessage should include.regex(errorMessage)
      case Success(_)         => fail("expected a failure")
    }

  def expectSuccess(configFile: String)(implicit pos: Position): Assertion =
    resultForConfig(configFile) match {
      case Failure(exception) => fail(s"Should not have any errors but was: ${exception.getMessage}")
      case Success(_)         => succeed
    }

  val data = "\\(.+\\)"

  "should fail if committee size is bigger than threshold" in {
    expectFailure(
      configFile = "conf/too_big_committee.conf",
      errorMessage = s"committee size $data must be less than or equal to the min-registration threshold $data"
    )
  }

  "should fail if sidechain genesis timestamp is earlier than mainchain epoch timestamp" in {
    expectFailure(
      configFile = "conf/invalid_start_timestamp.conf",
      errorMessage = s"The sidechain genesis $data is before the mainchain first epoch $data."
    )
  }

  "should fail if sidechain epoch is longer than mainchain epoch" in {
    expectFailure(
      configFile = "conf/sidechain_epoch_longer_than_mainchain_epoch.conf",
      errorMessage = s"the mainchain epoch duration $data must be longer then the sidechain epoch duration $data"
    )
  }

  "should fail if sidechain epoch duration does not divide mainchain epoch" in {
    expectFailure(
      configFile = "conf/sidechain_epoch_not_divisible.conf",
      errorMessage = s"Mainchain epoch duration $data should be a multiple of sidechain epoch duration $data"
    )
  }

  "should fail if sidechain epochs can belong to more than one mainchain epoch" in {
    expectFailure(
      configFile = "conf/sidechain_epoch_can_belong_to_multiple_mainchain_epoch.conf",
      errorMessage = s"Sidechain epochs $data must align with mainchain epoch boundaries $data."
    )
  }

  "should parse correct config without any errors" in {
    expectSuccess(configFile = "conf/test-chain.conf")
  }

  "should parse ada value correctly" in {
    assert(SidechainBlockchainConfig.parseAda("1 ada") == Lovelace.ofAda(1))
    assert(SidechainBlockchainConfig.parseAda("1000 ada") == Lovelace.ofAda(1000))
    assert(SidechainBlockchainConfig.parseAda("1 ADA") == Lovelace.ofAda(1))
    assert(SidechainBlockchainConfig.parseAda("1000 ADA") == Lovelace.ofAda(1000))
    assert(SidechainBlockchainConfig.parseAda("-1 ada") == Lovelace.ofAda(-1))
    assert(SidechainBlockchainConfig.parseAda("-1000 ada") == Lovelace.ofAda(-1000))

    assert(SidechainBlockchainConfig.parseAda("1 lovelace") == Lovelace(1))
    assert(SidechainBlockchainConfig.parseAda("1000 lovelace") == Lovelace(1000))
    assert(SidechainBlockchainConfig.parseAda("1 LOVELACE") == Lovelace(1))
    assert(SidechainBlockchainConfig.parseAda("1000 LOVELACE") == Lovelace(1000))
    assert(SidechainBlockchainConfig.parseAda("-1 lovelace") == Lovelace(-1))
    assert(SidechainBlockchainConfig.parseAda("-1000 lovelace") == Lovelace(-1000))
  }

  "should throw exception if the ada value is incorrect" in {
    intercept[IllegalArgumentException] {
      SidechainBlockchainConfig.parseAda("1 pln")
    }
    intercept[IllegalArgumentException] {
      SidechainBlockchainConfig.parseAda("1")
    }
    intercept[IllegalArgumentException] {
      SidechainBlockchainConfig.parseAda("1.123 lovelace")
    }
  }
}
