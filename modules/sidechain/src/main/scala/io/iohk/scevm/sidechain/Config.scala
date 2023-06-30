package io.iohk.scevm.sidechain

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.effect.IO
import cats.syntax.all._
import com.typesafe.config.Config
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{AbstractSignatureScheme, ECDSA, EdDSA, kec256}
import io.iohk.ethereum.rlp
import io.iohk.ethereum.rlp.RLPImplicitConversions._
import io.iohk.ethereum.rlp.RLPImplicits._
import io.iohk.ethereum.rlp.RLPList
import io.iohk.scevm.cardanofollower.CardanoFollowerConfig
import io.iohk.scevm.cardanofollower.datasource.DatasourceConfig.{CandidateConfig, DbSync}
import io.iohk.scevm.cardanofollower.datasource.{DatasourceConfig, Sensitive}
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.config.ConfigOps._
import io.iohk.scevm.config._
import io.iohk.scevm.db.storage.genesis.ObftGenesisLoader
import io.iohk.scevm.domain.{Address, ObftGenesisData, SidechainPrivateKey}
import io.iohk.scevm.trustlesssidechain.SidechainParams
import io.iohk.scevm.trustlesssidechain.cardano._
import io.iohk.scevm.utils.SystemTime.UnixTimestamp
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters.JavaDurationOps

final case class SidechainBlockchainConfig(
    override val forkBlockNumbers: ForkBlockNumbers,
    override val maxCodeSize: Option[BigInt],
    override val genesisData: ObftGenesisData,
    override val chainId: ChainId,
    override val networkId: Int,
    override val ethCompatibility: EthCompatibilityConfig,
    override val monetaryPolicyConfig: MonetaryPolicyConfig,
    override val slotDuration: FiniteDuration,
    override val stabilityParameter: Int,
    bridgeContractAddress: Address,
    epochStabilityMultiplier: Integer,
    committeeConfig: CommitteeConfig,
    mainchainConfig: CardanoFollowerConfig,
    initializationConfig: InitializationConfig,
    minimalCandidateStake: Lovelace,
    committeeWhitelist: Option[NonEmptyList[EdDSA.PublicKey]],
    thresholdNumerator: Integer,
    thresholdDenominator: Integer,
    signingScheme: AbstractSignatureScheme
) extends BlockchainConfig {

  val obftConfig: ObftConfig = ObftConfig(
    genesisTimestamp = genesisData.timestamp,
    stabilityParameter = stabilityParameter,
    epochDurationInSlot = stabilityParameter * epochStabilityMultiplier,
    slotDuration = slotDuration
  )

  import cats.effect.unsafe.implicits.global

  private val genesisHash = ObftGenesisLoader.getGenesisHash[IO](genesisData).unsafeRunSync()

  val sidechainParams: SidechainParams =
    SidechainParams(
      chainId,
      genesisHash,
      initializationConfig.genesisCommitteeUtxo,
      thresholdNumerator,
      thresholdDenominator
    )

  override def nodeCompatibilityHash: ByteString = {
    val checkedParameters = List[rlp.RLPEncodeable](
      initializationConfig.genesisCommitteeUtxo.txHash.value,
      initializationConfig.genesisCommitteeUtxo.index,
      initializationConfig.genesisMintUtxo.map(_.txHash.value),
      initializationConfig.genesisMintUtxo.map(_.index),
      thresholdNumerator.toInt,
      thresholdDenominator.toInt,
      minimalCandidateStake.value,
      committeeWhitelist.map(_.toList.map(_.bytes)),
      mainchainConfig.epochDuration.toMillis,
      mainchainConfig.slotDuration.toMillis,
      mainchainConfig.activeSlotCoeff.bigDecimal.unscaledValue().toByteArray,
      mainchainConfig.activeSlotCoeff.bigDecimal.scale(),
      mainchainConfig.firstEpochTimestamp,
      mainchainConfig.stabilityParameter,
      mainchainConfig.fuelAssetName.value,
      mainchainConfig.fuelMintingPolicyId.value,
      mainchainConfig.merkleRootNftPolicyId.value,
      mainchainConfig.committeeNftPolicyId.value,
      committeeConfig.committeeSize,
      committeeConfig.minRegisteredCandidates,
      committeeConfig.minStakeThreshold.value,
      obftConfig.epochDurationInSlot
    )
    ByteString(kec256(rlp.encode(RLPList(checkedParameters))))
  }
}

object SidechainBlockchainConfig {
  def fromRawConfig(config: Config): SidechainBlockchainConfig = {
    val baseBlockchainConfig = BaseBlockchainConfig.fromRawConfig(config)

    val rawConfig = config.getConfig("sidechain")
    val committeeWhitelist = rawConfig
      .optional(_.getStringList("committee-whitelist").asScala.toList.map(EdDSA.PublicKey.fromHexUnsafe))
      .map(
        NonEmptyList
          .fromList(_)
          .getOrElse(throw new RuntimeException("A committee-whitelist was provided but it is empty."))
      )

    val sidechainBlockchainConfig = SidechainBlockchainConfig(
      baseBlockchainConfig.forkBlockNumbers,
      baseBlockchainConfig.maxCodeSize,
      baseBlockchainConfig.genesisData,
      baseBlockchainConfig.chainId,
      baseBlockchainConfig.networkId,
      baseBlockchainConfig.ethCompatibility,
      baseBlockchainConfig.monetaryPolicyConfig,
      baseBlockchainConfig.slotDuration,
      baseBlockchainConfig.stabilityParameter,
      Address(rawConfig.getString("bridge-contract")),
      epochStabilityMultiplier = rawConfig.getInt("epoch-stability-multiplier"),
      committeeConfig = committeeConfig(rawConfig),
      mainchainConfig = cardanoFollowerConfig(rawConfig),
      initializationConfig = initializationParams(rawConfig.getConfig("initialization")),
      minimalCandidateStake = parseAda(rawConfig.getString("min-candidate-stake")),
      committeeWhitelist = committeeWhitelist,
      thresholdNumerator = rawConfig.getInt("threshold.numerator"),
      thresholdDenominator = rawConfig.getInt("threshold.denominator"),
      signingScheme = parseSigningScheme(rawConfig.getString("signing.scheme"))
    )

    validate(sidechainBlockchainConfig) match {
      case Validated.Valid(value) => value
      case Validated.Invalid(errors) =>
        throw new RuntimeException("Sidechain configuration is invalid:\n - " + errors.toList.mkString("\n - "))
    }
  }

  def parseAda(input: String): Lovelace = {
    val adaRegex      = raw"(-?\d+) (?:ada|ADA)".r
    val lovelaceRegex = raw"(-?\d+) (?:LOVELACE|lovelace)".r
    input match {
      case adaRegex(amount)      => Lovelace.ofAda(amount.toLong)
      case lovelaceRegex(amount) => Lovelace(amount.toLong)
      case other =>
        throw new IllegalArgumentException(s"Expected ada value in form of `\\d+ ada/lovelace` but got $other")
    }
  }

  def parseSigningScheme(str: String): AbstractSignatureScheme = str match {
    case "ecdsa" => ECDSA
    case other   => throw new IllegalArgumentException(s"'$other' is not recognized signing scheme'")
  }

  private def initializationParams(config: Config) =
    InitializationConfig(
      genesisMintUtxo = config
        .getStringOpt("genesis-mint-utxo")
        .map(s => UtxoId.parseUnsafe(s)),
      genesisCommitteeUtxo = UtxoId.parseUnsafe(config.getString("genesis-committee-utxo"))
    )

  private def committeeConfig(config: Config): CommitteeConfig = {
    val rawConfig = config.getConfig("committee")
    CommitteeConfig(
      minRegisteredCandidates = rawConfig.getInt("min-registered-candidates"),
      committeeSize = rawConfig.getInt("size"),
      minStakeThreshold = parseAda(rawConfig.getString("min-stake-threshold"))
    )
  }

  private def cardanoFollowerConfig(config: Config): CardanoFollowerConfig = {
    val rawConfig = config.getConfig("cardano-follower")
    CardanoFollowerConfig(
      stabilityParameter = rawConfig.getLong("stability-parameter"),
      firstEpochTimestamp = rawConfig.getLong("first-epoch-timestamp-millis").millisToTs,
      firstEpochNumber = rawConfig.getLong("first-epoch-number"),
      firstSlot = MainchainSlot(rawConfig.getLong("first-slot")),
      epochDuration = rawConfig.getLong("epoch-duration-seconds").seconds,
      slotDuration = rawConfig.getLong("slot-duration-seconds").seconds,
      activeSlotCoeff = BigDecimal(rawConfig.getString("active-slot-coeff")),
      committeeCandidateAddress = MainchainAddress(rawConfig.getString("committee-candidate-contract")),
      fuelMintingPolicyId = PolicyId.fromHexUnsafe(rawConfig.getString("fuel-minting-policy-id")),
      fuelAssetName = AssetName.fromHexUnsafe(rawConfig.getString("fuel-asset-name-hex")),
      committeeNftPolicyId = PolicyId.fromHexUnsafe(rawConfig.getString("committee-hash-nft-policy-id")),
      merkleRootNftPolicyId = PolicyId.fromHexUnsafe(rawConfig.getString("merkle-root-nft-policy-id")),
      checkpointNftPolicyId = PolicyId.fromHexUnsafe(rawConfig.getString("checkpoint-nft-policy-id"))
    )
  }

  def validate(config: SidechainBlockchainConfig): Validated[NonEmptyList[String], SidechainBlockchainConfig] =
    List(
      sidechainStartsAfterMainchain(_),
      mainchainEpochIsLongerThanSidechainsEpoch(_),
      committeeSizeIsLessThanOrEqualToMinRegistrationThreshold(_),
      activeSlotCoeffIsMoreThan0AndLessOrEqualTo1(_),
      slotDurationMustDivideEpochDuration(_),
      sidechainEpochDurationMustDivideMainchainEpoch(_),
      sidechainEpochMustBelongToSingleMainchainEpoch(_),
      thresholdRatioMustBeBetweenZeroAndOne(_)
    )
      .traverse(_.apply(config))
      .as(config)

  private def validWhen(cond: Boolean, msg: String): ValidatedNel[String, Unit] =
    Validated.condNel(cond, (), msg)

  private def sidechainStartsAfterMainchain(config: SidechainBlockchainConfig): ValidatedNel[String, Unit] = {
    val mainchainGenesis = config.mainchainConfig.firstEpochTimestamp
    val sidechainGenesis = config.obftConfig.genesisTimestamp
    validWhen(
      sidechainGenesis >= mainchainGenesis,
      s"The sidechain genesis ($sidechainGenesis) is before the mainchain first epoch ($mainchainGenesis)."
    )
  }

  private def mainchainEpochIsLongerThanSidechainsEpoch(
      config: SidechainBlockchainConfig
  ): ValidatedNel[String, Unit] = {
    val sidechainEpoch = config.obftConfig.epochDuration
    val mainchainEpoch = config.mainchainConfig.epochDuration
    validWhen(
      mainchainEpoch >= sidechainEpoch,
      s"the mainchain epoch duration ($mainchainEpoch) must be longer then the sidechain epoch duration ($sidechainEpoch)"
    )
  }

  private def committeeSizeIsLessThanOrEqualToMinRegistrationThreshold(
      config: SidechainBlockchainConfig
  ): ValidatedNel[String, Unit] = {
    val committee        = config.committeeConfig.committeeSize
    val minRegCandidates = config.committeeConfig.minRegisteredCandidates
    validWhen(
      committee <= minRegCandidates,
      s"committee size ($committee) must be less than or equal to the min-registration threshold ($minRegCandidates)"
    )
  }

  private def activeSlotCoeffIsMoreThan0AndLessOrEqualTo1(
      config: SidechainBlockchainConfig
  ): ValidatedNel[String, Unit] = {
    val coeff = config.mainchainConfig.activeSlotCoeff
    validWhen(
      coeff > 0 && coeff <= 1,
      s"active slot coefficient ($coeff) must be in range (0; 1]"
    )
  }

  private def slotDurationMustDivideEpochDuration(config: SidechainBlockchainConfig): ValidatedNel[String, Unit] = {
    val epoch = config.mainchainConfig.epochDuration.toMillis
    val slot  = config.mainchainConfig.slotDuration.toMillis
    validWhen(
      epoch % slot == 0,
      s"The mainchain epoch duration ($epoch) should be a multiple of the slot duration ($slot)"
    )
  }

  private def sidechainEpochDurationMustDivideMainchainEpoch(
      config: SidechainBlockchainConfig
  ): ValidatedNel[String, Unit] = {
    val mainchainEpoch = config.mainchainConfig.epochDuration.toMillis
    val sidechainEpoch = config.obftConfig.epochDuration.toMillis
    validWhen(
      mainchainEpoch % sidechainEpoch == 0,
      s"Mainchain epoch duration ($mainchainEpoch) should be a multiple of sidechain epoch duration ($sidechainEpoch)"
    )
  }

  private def sidechainEpochMustBelongToSingleMainchainEpoch(
      config: SidechainBlockchainConfig
  ): ValidatedNel[String, Unit] = {
    val sidechainGenesis = config.obftConfig.genesisTimestamp.millis
    val mainchainGenesis = config.mainchainConfig.firstEpochTimestamp.millis
    val sidechainEpoch   = config.obftConfig.epochDuration.toMillis
    val mainchainEpoch   = config.mainchainConfig.epochDuration.toMillis
    validWhen(
      (sidechainGenesis - mainchainGenesis) % sidechainEpoch == 0 && mainchainEpoch % sidechainEpoch == 0,
      s"Sidechain epochs (starting at $sidechainGenesis, step $sidechainEpoch) must align with " +
        s"mainchain epoch boundaries (starting at $mainchainGenesis, step $mainchainEpoch)."
    )
  }

  private def thresholdRatioMustBeBetweenZeroAndOne(config: SidechainBlockchainConfig) = {
    val numerator   = config.sidechainParams.thresholdNumerator
    val denominator = config.sidechainParams.thresholdDenominator
    Validated.condNel(0 < numerator && numerator <= denominator, (), "threshold ratio has to be in (0, 1]")
  }
}

final case class ExtraSidechainConfig(
    crossChainSigning: CrossChainSigningConfig,
    datasource: DatasourceConfig,
    electionCache: ElectionCacheConfig,
    observabilityConfig: ObservabilityConfig
)

final case class CrossChainSigningConfig(
    signingScheme: AbstractSignatureScheme
)

final case class ElectionCacheConfig(
    validData: ElectionCacheConfig.ValidData,
    invalidData: ElectionCacheConfig.InvalidData,
    cacheSize: Long
)

object ElectionCacheConfig {
  final case class ValidData(initial: FiniteDuration, refresh: FiniteDuration)

  final case class InvalidData(initial: FiniteDuration, refresh: FiniteDuration)
}

final case class ObftConfig(
    genesisTimestamp: UnixTimestamp,
    stabilityParameter: Int,
    slotDuration: FiniteDuration,
    epochDurationInSlot: Int
) {
  def epochDuration: FiniteDuration = slotDuration * epochDurationInSlot
}

final case class InitializationConfig(genesisMintUtxo: Option[UtxoId], genesisCommitteeUtxo: UtxoId)

object ExtraSidechainConfig {

  def fromConfig(config: Config, blockchainConfig: BlockchainConfig): ExtraSidechainConfig = {
    val maybeSidechainConfig = Option(blockchainConfig).collect { case sc: SidechainBlockchainConfig => sc }
    val rawConfig            = config.getConfig("sidechain")
    ExtraSidechainConfig(
      datasource = datasourceConfig(rawConfig.getConfig("datasource")),
      electionCache = electionCacheConfig(rawConfig),
      observabilityConfig = observability(rawConfig.getConfig("observability")),
      crossChainSigning = CrossChainSigningConfig(
        signingScheme = maybeSidechainConfig.fold[AbstractSignatureScheme](ECDSA)(_.signingScheme)
      )
    )
  }

  private def observability(rawConfig: Config) =
    ObservabilityConfig(
      waitingPeriod = rawConfig.getDuration("waiting-period").toScala,
      slotLagWarningThresholdInBlocks = rawConfig.getInt("slot-lag-warning-threshold-in-blocks"),
      handoverLagThreshold = rawConfig.getInt("handover-lag-warning-threshold")
    )

  private def electionCacheConfig(config: Config) = {
    val rawConfig = config.getConfig("election-cache")
    ElectionCacheConfig(
      validData = ElectionCacheConfig.ValidData(
        initial = rawConfig.getDuration("valid-ttl.initial").toScala,
        refresh = rawConfig.getDuration("valid-ttl.refresh").toScala
      ),
      invalidData = ElectionCacheConfig.InvalidData(
        initial = rawConfig.getDuration("invalid-ttl.initial").toScala,
        refresh = rawConfig.getDuration("invalid-ttl.refresh").toScala
      ),
      cacheSize = rawConfig.getLong("max-size")
    )
  }

  private def dbSyncConfig(dbSyncConfig: Config) =
    DbSync(
      username = dbSyncConfig.getString("username"),
      password = Sensitive(dbSyncConfig.getString("password")),
      url = url(dbSyncConfig),
      driver = dbSyncConfig.getString("driver"),
      connectThreadPoolSize = dbSyncConfig.getInt("connect-thread-pool-size")
    )

  private def datasourceConfig(config: Config): DatasourceConfig =
    config.getString("type") match {
      case dbSync @ "db-sync" =>
        dbSyncConfig(config.getConfig(dbSync))
      case mock @ "mock" =>
        val mockConfig       = config.getConfig(mock)
        val incomingTxConfig = mockConfig.getConfig("incoming-tx")
        DatasourceConfig.Mock(
          mockConfig.getInt("seed"),
          mockConfig.getInt("number-of-slots"),
          incomingTxConfig.getInt("min-token-amount"),
          incomingTxConfig.getInt("max-token-amount"),
          incomingTxConfig.getInt("max-transactions-per-block"),
          mockConfig
            .getConfigList("candidates")
            .asScala
            .map { candidateConfig =>
              CandidateConfig(
                EdDSA.PrivateKey.fromHexUnsafe(candidateConfig.getString("mainchain-private-key")),
                SidechainPrivateKey.fromHexUnsafe(candidateConfig.getString("sidechain-private-key")),
                UtxoId.parseUnsafe(candidateConfig.getString("consumed-utxo"))
              )
            }
            .toList
        )
      case other => throw new IllegalArgumentException(s"Unknown datasource type type=$other")
    }

  private def url(dbSyncConfig: Config) = {
    val host   = dbSyncConfig.getString("host")
    val port   = dbSyncConfig.getInt("port")
    val dbName = dbSyncConfig.getString("name")
    s"jdbc:postgresql://$host:$port/$dbName"
  }
}

final case class CommitteeConfig(minRegisteredCandidates: Int, committeeSize: Int, minStakeThreshold: Lovelace)

final case class ObservabilityConfig(
    waitingPeriod: FiniteDuration,
    slotLagWarningThresholdInBlocks: Int,
    handoverLagThreshold: Int
)
