package io.iohk.scevm.consensus.pos

import com.typesafe.config.Config
import io.iohk.bytes.FromHex
import io.iohk.ethereum.crypto.{AbstractPrivateKey, AbstractSignatureScheme}
import io.iohk.scevm.config.ConfigOps._
import io.iohk.scevm.domain.SidechainPrivateKey

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters.JavaDurationOps
import scala.util.Try

final case class PoSConfig(
    /** Private keys used by this node to sign blocks */
    validatorPrivateKeys: List[PoSConfig.KeySet],
    metrics: PoSConfig.Metrics
)

object PoSConfig {
  final private val TimeToFinalizationTrackerTimeout = "time-to-finalization-tracker-timeout"

  def fromConfig[SignatureScheme <: AbstractSignatureScheme](
      config: Config
  )(implicit privateKeyFromHex: FromHex[SignatureScheme#PrivateKey]): PoSConfig = {
    val poSConfig = config.getConfig("pos")
    val validatorPrivateKey: List[KeySet] =
      Try(poSConfig.getObjectList("private-keys").asScala.toList)
        .getOrElse(Nil)
        .flatMap(keySetConf => KeySet.fromConfig(keySetConf.toConfig))
    val metricsConfig                    = poSConfig.getConfig("metrics")
    val timeToFinalizationTrackerTimeout = metricsConfig.getDuration(TimeToFinalizationTrackerTimeout).toScala

    PoSConfig(
      validatorPrivateKey,
      Metrics(
        timeToFinalizationTrackerTimeout
      )
    )
  }

  final case class KeySet(
      leaderPrvKey: SidechainPrivateKey,
      crossChainPrvKey: Option[AbstractPrivateKey[_]]
  )

  object KeySet {
    def fromConfig[SignatureScheme <: AbstractSignatureScheme](
        config: Config
    )(implicit privateKeyFromHex: FromHex[SignatureScheme#PrivateKey]): Option[KeySet] =
      Option.unless(config.isEmpty)(
        KeySet(
          SidechainPrivateKey.fromHexUnsafe(config.getString("leader")),
          config.getStringOpt("cross-chain").map(privateKeyFromHex.apply)
        )
      )

  }

  final case class Metrics(
      timeToFinalizationTrackerTimeout: FiniteDuration
  )
}
