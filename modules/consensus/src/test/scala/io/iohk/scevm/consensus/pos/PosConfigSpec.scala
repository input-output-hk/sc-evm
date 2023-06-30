package io.iohk.scevm.consensus.pos

import com.softwaremill.diffx.generic.auto.diffForCaseClass
import com.softwaremill.diffx.scalatest.DiffShouldMatcher
import com.typesafe.config.ConfigFactory
import io.iohk.ethereum.crypto.ECDSA
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PosConfigSpec extends AnyWordSpec with Matchers with DiffShouldMatcher {
  "PosConfig.fromConfig" should {
    "read passive conf" in {
      val rawConfig = ConfigFactory.parseResources("conf/pos/passive.conf")
      val posConfig = PoSConfig.fromConfig[ECDSA](rawConfig)

      assert(posConfig.validatorPrivateKeys.isEmpty)
    }

    "read single private key conf" in {
      val rawConfig = ConfigFactory.parseResources("conf/pos/single-key.conf")
      val posConfig = PoSConfig.fromConfig[ECDSA](rawConfig)

      assert(posConfig.validatorPrivateKeys.size == 1)
    }

    "read multiple private keys conf" in {
      val rawConfig = ConfigFactory.parseResources("conf/pos/list-keys.conf")
      val posConfig = PoSConfig.fromConfig[ECDSA](rawConfig)

      assert(posConfig.validatorPrivateKeys.size > 1)
    }
  }

  "PoSConfig.KeySet.fromConfig" should {
    val ecdsaKeyHex = "f46bf49093d585f2ea781a0bf6d83468919f547999ad91c9210256979d88eef1"
    val ecdsaKey    = ECDSA.PrivateKey.fromHexUnsafe(ecdsaKeyHex)

    "read a set with just leader key" in {
      val rawConfig = ConfigFactory.parseString(s"{leader: $ecdsaKeyHex}")
      val keySet    = PoSConfig.KeySet.fromConfig[ECDSA](rawConfig)
      keySet shouldMatchTo Some(PoSConfig.KeySet(ecdsaKey, None))
    }

    "read a set with both leader and cross-chain key" in {
      val rawConfig = ConfigFactory.parseString(s"{leader: $ecdsaKeyHex, cross-chain: $ecdsaKeyHex}")
      val keySet    = PoSConfig.KeySet.fromConfig[ECDSA](rawConfig)
      keySet shouldMatchTo Some(PoSConfig.KeySet(ecdsaKey, Some(ecdsaKey)))
    }

    "ignore empty config objects" in {
      val rawConfig = ConfigFactory.parseString(s"{}")
      val keySet    = PoSConfig.KeySet.fromConfig[ECDSA](rawConfig)
      keySet shouldMatchTo None
    }

    "throw an exception if leader key is missing and there is other data" in {
      val rawConfig = ConfigFactory.parseString(s"{cross-chain: $ecdsaKeyHex}")
      an[Exception] shouldBe thrownBy(
        PoSConfig.KeySet.fromConfig[ECDSA](rawConfig)
      )
    }
  }
}
