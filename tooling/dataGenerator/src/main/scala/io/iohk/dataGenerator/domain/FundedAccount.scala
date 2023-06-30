package io.iohk.dataGenerator.domain

import io.iohk.scevm.domain.{Address, SidechainPrivateKey, SidechainPublicKey}

final case class FundedAccount(privateKey: SidechainPrivateKey) {
  lazy val publicKey: SidechainPublicKey = SidechainPublicKey.fromPrivateKey(privateKey)
  lazy val toAddress: Address            = Address.fromPublicKey(publicKey)
}
