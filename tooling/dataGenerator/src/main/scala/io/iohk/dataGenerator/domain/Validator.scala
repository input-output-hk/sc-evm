package io.iohk.dataGenerator.domain

import com.typesafe.config.Config
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.domain.{SidechainPrivateKey, SidechainPublicKey}
import org.bouncycastle.crypto.prng.FixedSecureRandom

import scala.jdk.CollectionConverters._

final case class Validator(publicKey: SidechainPublicKey, privateKey: SidechainPrivateKey)

object Validator {
  def generate(pubKeys: IndexedSeq[SidechainPublicKey]): IndexedSeq[Validator] =
    pubKeys.map { pubKeyUseAsSeed =>
      val (privateKey, publicKey) =
        ECDSA.generateKeyPair(new FixedSecureRandom(pubKeyUseAsSeed.bytes.toArray[Byte]))
      Validator(publicKey, privateKey)
    }

  def fromConfig(config: Config): IndexedSeq[Validator] = {
    val standalone = config.getConfig("standalone")
    val privateKeys =
      standalone.getStringList("privateKeys").asScala.toIndexedSeq.map(SidechainPrivateKey.fromHexUnsafe)

    privateKeys.map { privateKey =>
      Validator(SidechainPublicKey.fromPrivateKey(privateKey), privateKey)
    }
  }
}
