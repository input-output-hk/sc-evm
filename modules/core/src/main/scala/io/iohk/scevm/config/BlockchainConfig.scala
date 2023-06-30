package io.iohk.scevm.config

import cats.Show
import com.typesafe.config.{Config => TypesafeConfig, ConfigRenderOptions}
import io.estatico.newtype.macros.newtype
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.config.ConfigOps._
import io.iohk.scevm.domain.{Address, BlockNumber, ObftGenesisData, SidechainPublicKey, UInt256}

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters.JavaDurationOps
import scala.util.Try

trait BlockchainConfig {
  def forkBlockNumbers: ForkBlockNumbers
  def maxCodeSize: Option[BigInt]
  def genesisData: ObftGenesisData
  def chainId: ChainId
  def networkId: Int
  def ethCompatibility: EthCompatibilityConfig
  def monetaryPolicyConfig: MonetaryPolicyConfig
  def slotDuration: FiniteDuration
  def stabilityParameter: Int

  def nodeCompatibilityHash: ByteString
}

final case class BaseBlockchainConfig(
    forkBlockNumbers: ForkBlockNumbers,
    maxCodeSize: Option[BigInt],
    genesisData: ObftGenesisData,
    chainId: ChainId,
    networkId: Int,
    ethCompatibility: EthCompatibilityConfig,
    monetaryPolicyConfig: MonetaryPolicyConfig,
    slotDuration: FiniteDuration,
    stabilityParameter: Int
)

object BaseBlockchainConfig {
  def fromRawConfig(blockchainConfig: TypesafeConfig): BaseBlockchainConfig = {
    val maxCodeSize: Option[BigInt] = Try(BigInt(blockchainConfig.getString("max-code-size"))).toOption

    val chainId: ChainId = {
      val s = blockchainConfig.getString("chain-id")
      val n = Hex.parseHexNumberUnsafe(s)
      ChainId.unsafeFrom(n)
    }

    val networkId: Int = blockchainConfig.getInt("network-id")

    val ethCompatibilityConfig = EthCompatibilityConfig(
      difficulty = UInt256(BigInt(blockchainConfig.getString("eth-compatibility.difficulty"))),
      blockBaseFee = UInt256(BigInt(blockchainConfig.getString("eth-compatibility.block-base-fee")))
    )

    val monetaryPolicyConfig = MonetaryPolicyConfig(blockchainConfig.getConfig("monetary-policy"))
    val genesisData = GenesisDataJson.fromJsonString(
      blockchainConfig.getConfig("genesis-data").root().render(ConfigRenderOptions.concise())
    )

    val slotDuration: FiniteDuration = blockchainConfig.getDuration("slot-duration").toScala
    val stabilityParameter: Int      = blockchainConfig.getInt("stability-parameter-k")

    BaseBlockchainConfig(
      forkBlockNumbers = ForkBlockNumbers.Full,
      maxCodeSize = maxCodeSize,
      genesisData = genesisData,
      chainId = chainId,
      networkId = networkId,
      ethCompatibility = ethCompatibilityConfig,
      monetaryPolicyConfig = monetaryPolicyConfig,
      slotDuration = slotDuration,
      stabilityParameter = stabilityParameter
    )
  }
}

final case class StandaloneBlockchainConfig(
    override val forkBlockNumbers: ForkBlockNumbers,
    override val maxCodeSize: Option[BigInt],
    override val genesisData: ObftGenesisData,
    override val chainId: ChainId,
    override val networkId: Int,
    override val ethCompatibility: EthCompatibilityConfig,
    override val monetaryPolicyConfig: MonetaryPolicyConfig,
    override val slotDuration: FiniteDuration,
    override val stabilityParameter: Int,
    /** Ordered list of validators */
    validators: IndexedSeq[SidechainPublicKey]
) extends BlockchainConfig {

  override def nodeCompatibilityHash: ByteString = ByteString(0)
}

object StandaloneBlockchainConfig {
  def fromRawConfig(blockchainConfig: TypesafeConfig): StandaloneBlockchainConfig = {
    val baseBlockchainConfig = BaseBlockchainConfig.fromRawConfig(blockchainConfig)

    val standalone = blockchainConfig.getConfig("standalone")
    val validators = standalone.getStringList("validators").asScala.toVector.map(SidechainPublicKey.fromHexUnsafe)

    StandaloneBlockchainConfig(
      baseBlockchainConfig.forkBlockNumbers,
      baseBlockchainConfig.maxCodeSize,
      baseBlockchainConfig.genesisData,
      baseBlockchainConfig.chainId,
      baseBlockchainConfig.networkId,
      baseBlockchainConfig.ethCompatibility,
      baseBlockchainConfig.monetaryPolicyConfig,
      baseBlockchainConfig.slotDuration,
      baseBlockchainConfig.stabilityParameter,
      validators
    )
  }
}

object BlockchainConfig {

  import scala.language.implicitConversions
  @newtype final case class ChainId(value: Byte)

  object ChainId {
    implicit val show: Show[ChainId] = _.value.toString
    def unsafeFrom(bi: BigInt): ChainId = from(bi) match {
      case Left(value)  => throw new IllegalArgumentException(value)
      case Right(value) => value
    }

    def from(n: BigInt): Either[String, ChainId] =
      Either.cond(n >= 0 && n <= 127, ChainId(n.toByte), "chain-id must be a number in range [0, 127]")

    def unapply(chainId: ChainId): Option[Byte] = Some(chainId.value)
  }
}

final case class ForkBlockNumbers(
    frontierBlockNumber: BlockNumber,
    homesteadBlockNumber: BlockNumber,
    eip150BlockNumber: BlockNumber,
    spuriousDragonBlockNumber: BlockNumber,
    byzantiumBlockNumber: BlockNumber,
    constantinopleBlockNumber: BlockNumber,
    petersburgBlockNumber: BlockNumber,
    istanbulBlockNumber: BlockNumber,
    berlinBlockNumber: BlockNumber,
    londonBlockNumber: BlockNumber
) {
  def all: List[BlockNumber] = this.productIterator.toList.flatMap {
    case n: BigInt => Some(BlockNumber(n))
    case _         => None
  }
}

object ForkBlockNumbers {
  val Full: ForkBlockNumbers = ForkBlockNumbers(
    frontierBlockNumber = BlockNumber(0),
    homesteadBlockNumber = BlockNumber(0),
    eip150BlockNumber = BlockNumber(0),
    spuriousDragonBlockNumber = BlockNumber(0),
    byzantiumBlockNumber = BlockNumber(0),
    constantinopleBlockNumber = BlockNumber(0),
    petersburgBlockNumber = BlockNumber(0),
    berlinBlockNumber = BlockNumber(0),
    londonBlockNumber = BlockNumber(0),
    istanbulBlockNumber = BlockNumber(0)
  )
}

/** values needed to ensure compatibility with the ETH virtual machine */
final case class EthCompatibilityConfig(difficulty: UInt256, blockBaseFee: UInt256)

final case class MonetaryPolicyConfig(blockReward: BigInt, rewardAddress: Option[Address])

object MonetaryPolicyConfig {
  def apply(mpConfig: TypesafeConfig): MonetaryPolicyConfig =
    MonetaryPolicyConfig(
      BigInt(mpConfig.getString("block-reward")),
      mpConfig.getStringOpt("fixed-reward-address").map(Address(_))
    )
}
