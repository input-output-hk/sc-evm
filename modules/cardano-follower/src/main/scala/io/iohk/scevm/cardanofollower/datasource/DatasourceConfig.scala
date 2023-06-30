package io.iohk.scevm.cardanofollower.datasource

import cats.Show
import io.iohk.ethereum.crypto.EdDSA
import io.iohk.scevm.domain.SidechainPrivateKey
import io.iohk.scevm.trustlesssidechain.cardano.UtxoId

sealed trait DatasourceConfig

object DatasourceConfig {
  final case class DbSync(
      username: String,
      password: Sensitive,
      url: String,
      driver: String,
      connectThreadPoolSize: Int
  ) extends DatasourceConfig

  final case class CandidateConfig(
      mainchainPrvKey: EdDSA.PrivateKey,
      sidechainPrvKey: SidechainPrivateKey,
      consumedInput: UtxoId
  )

  final case class Mock(
      seed: Int,
      numberOfSlots: Int,
      minTokenAmount: Int,
      maxTokenAmount: Int,
      maxNumberOfTransactionsPerBlock: Int,
      candidates: List[CandidateConfig]
  ) extends DatasourceConfig
}

final case class Sensitive(value: String) extends AnyVal {
  override def toString: String = "***"
}

object Sensitive {
  implicit val showSensitive: Show[Sensitive] = Show.fromToString
}
