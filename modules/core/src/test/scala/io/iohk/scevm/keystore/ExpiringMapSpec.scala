package io.iohk.scevm.keystore

import io.iohk.scevm.domain.{Account, Address}
import io.iohk.scevm.testing.NormalPatience
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Duration

class ExpiringMapSpec extends AnyWordSpec with Matchers with Eventually with NormalPatience {

  val testResolution        = 20
  val holdTime              = 100
  val holdTimeDur: Duration = Duration.ofMillis(holdTime)

  "ExpiringMap" should {
    "Put element in, for the correct amount of time and do not retain it afterwards" in new TestSetup {
      expiringMap.add(address1, account1, holdTimeDur)

      expiringMap.get(address1) shouldEqual Some(account1)

      eventually {
        expiringMap.get(address1) shouldBe None
      }

      expiringMap.get(address1) shouldBe None
    }

    "Put element in, for a negative duration (element will be inaccessible and removed at first occasion)" in new TestSetup {
      expiringMap.add(address1, account1, Duration.ofMillis(-50))

      expiringMap.get(address1) shouldEqual None
    }

    "Put new element in with new holdTime, for the correct amount of time and do not retain it afterwards" in new TestSetup {
      expiringMap.add(address1, account1, holdTimeDur)

      expiringMap.get(address1) shouldEqual Some(account1)

      eventually {
        expiringMap.get(address1) shouldBe None
      }

      expiringMap.get(address1) shouldBe None

      expiringMap.add(address1, account2, holdTimeDur)

      expiringMap.get(address1) shouldEqual Some(account2)

      eventually {
        expiringMap.get(address1) shouldBe None
      }

      expiringMap.get(address1) shouldBe None
    }

    "Put two elements in, for different amounts of time and do not retain them afterwards" in new TestSetup {
      expiringMap.add(address1, account1, holdTimeDur)
      expiringMap.add(address2, account2, holdTimeDur.plusMillis(patienceConfig.interval.toMillis * 2))
      expiringMap.get(address1) shouldEqual Some(account1)
      expiringMap.get(address2) shouldEqual Some(account2)

      eventually {
        expiringMap.get(address1) shouldBe None
        expiringMap.get(address2) shouldEqual Some(account2)
      }

      eventually {
        expiringMap.get(address2) shouldBe None
      }
    }

    "Put element in, for the default amount of time and do not retain it afterwards" in new TestSetup {
      expiringMap.add(address1, account1)

      expiringMap.get(address1) shouldEqual Some(account1)

      eventually {
        expiringMap.get(address1) shouldBe None
      }

      expiringMap.get(address1) shouldBe None
    }

    "Put element in, until some time and do not retain it afterwards" in new TestSetup {
      expiringMap.addFor(address1, account1, holdTimeDur)

      expiringMap.get(address1) shouldEqual Some(account1)

      eventually {
        expiringMap.get(address1) shouldBe None
      }

      expiringMap.get(address1) shouldBe None
    }

    "Do not overflow nor throw exception when adding duration with max seconds" in new TestSetup {
      expiringMap.add(address1, account1, Duration.ofSeconds(Long.MaxValue))

      expiringMap.get(address1) shouldEqual Some(account1)
    }

    "It should remove existing element" in new TestSetup {
      expiringMap.add(address1, account1, holdTimeDur)
      expiringMap.get(address1) shouldEqual Some(account1)
      expiringMap.remove(address1)
      expiringMap.get(address1) shouldBe None
    }

    "It should overwrite existing element" in new TestSetup {
      expiringMap.add(address1, account1, holdTimeDur)
      expiringMap.get(address1) shouldEqual Some(account1)
      expiringMap.add(address1, account2, holdTimeDur)
      expiringMap.get(address1) shouldBe Some(account2)
    }
  }

  trait TestSetup {
    val defaultHoldTime                            = holdTime
    val expiringMap: ExpiringMap[Address, Account] = ExpiringMap.empty(Duration.ofMillis(defaultHoldTime))
    val address1: Address                          = Address(0x123456)
    val address2: Address                          = Address(0xabcdef)

    val account1: Account = Account.empty()
    val account2: Account = Account.empty().increaseBalance(10)
  }
}
