package io.iohk.consensus

import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.config._
import io.iohk.scevm.consensus.pos.PoSConfig
import io.iohk.scevm.domain.{Address, Nonce, ObftGenesisAccount, ObftGenesisData, UInt256}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp

import scala.concurrent.duration._

/** For testing purposes, we generate the public keys from the private ones.
  * The private keys are used to generate valid blocks, while the public keys are required by the chain validation
  */
object ConfigFixture {

  private lazy val prvKeys: Seq[ECDSA.PrivateKey] = List(
    "5748dbf686c90fefc9a91b1c504e969b68882bb88bda22c586c57f13938766d5",
    "c32833200e0b66439b2b9b8efaaeb8fb72d44d777e07e9ab6987df4077bcb7ae",
    "a4f37bb221ad9c474f7b91f0b3a8de5e9c4345d1bf24dbb4df1f9b487a23fff8",
    "85d38f62e7ffbc0519a8e66eae313800896d80d4399942234e698de5fcd97502"
  ).map(ECDSA.PrivateKey.fromHexUnsafe)

  lazy val pubPrvKeyMapping: Map[ECDSA.PublicKey, ECDSA.PrivateKey] = prvKeys.map { prvKey =>
    val pubKey = ECDSA.PublicKey.fromPrivateKey(prvKey)
    (pubKey, prvKey)
  }.toMap

  val validators: Vector[ECDSA.PublicKey] = pubPrvKeyMapping.keys.toVector

  lazy val posConfig: PoSConfig = PoSConfig(
    validatorPrivateKeys = List.empty,
    metrics = PoSConfig.Metrics(1.second)
  )

  lazy val ethCompatConfig: EthCompatibilityConfig = EthCompatibilityConfig(0, 0)
  // setting reward to 0 because it is not possible to predict the state root for children otherwise.
  lazy val monetaryConfig: MonetaryPolicyConfig = MonetaryPolicyConfig(0, None)

  lazy val account: ObftGenesisAccount = ObftGenesisAccount(UInt256(BigInt(2).pow(200)), None, None, Map.empty)

  lazy val genesisData: ObftGenesisData = ObftGenesisData(
    Address(0),
    0x7a1200,
    UnixTimestamp.fromSeconds(1604534400),
    Map(
      Address("075b073eaa848fc14de2fd9c2a18d97a4783d84c") -> account,
      Address("18f496690eb3fabb794a653be8097af5331c07b1") -> account,
      Address("1ff7fc39f7f4dc79c5867b9514d0e42607741384") -> account,
      Address("75b0f1c605223de2a013bf7f26d4cd93c63a5eb8") -> account,
      Address("7e3166074d45c3311908315da1af5f5a965b7ece") -> account
    ),
    Nonce.Zero
  )

  lazy val blockchainConfig: StandaloneBlockchainConfig = StandaloneBlockchainConfig(
    forkBlockNumbers = ForkBlockNumbers.Full,
    maxCodeSize = None,
    genesisData = genesisData,
    chainId = ChainId(77),
    networkId = 99,
    ethCompatibility = ethCompatConfig,
    monetaryPolicyConfig = monetaryConfig,
    slotDuration = 30.seconds,
    stabilityParameter = 10,
    IndexedSeq.empty
  )

}
