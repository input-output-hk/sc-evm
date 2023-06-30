package io.iohk.cli

import cats.data.EitherT
import cats.effect.{ExitCode, IO}
import com.monovore.decline.Opts
import com.typesafe.config.ConfigFactory
import io.circe.Encoder
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.config.ConfigOps._
import io.iohk.scevm.config.StandaloneBlockchainConfig
import io.iohk.scevm.db.storage.genesis.ObftGenesisLoader
import io.iohk.scevm.domain.BlockHash
import io.iohk.scevm.sidechain.SidechainBlockchainConfig
import io.iohk.scevm.trustlesssidechain.cardano.UtxoId

import java.nio.file.Path

object GetChainParams {

  private val fileArg = Opts.option[Path]("chain-file", "hocon configuration file to read", short = "c")

  private val opts: Opts[Path] = Opts.subcommand("get-chain-params", "Get the chain params for a sidechain") {
    fileArg
  }

  final private case class Output(
      chainId: ChainId,
      genesisHash: BlockHash,
      genesisMint: Option[UtxoId],
      genesisUtxo: UtxoId,
      threshold: Threshold
  )

  final private case class Threshold(numerator: Int, denominator: Int)

  private object Output {
    implicit val encoder: Encoder[Output] = {
      implicit val utxoIdEncoder: Encoder[UtxoId] = Encoder[String].contramap[UtxoId]({ case UtxoId(txHash, index) =>
        Hex.toHexString(txHash.value) + "#" + index
      })
      implicit val chainIdEncoder: Encoder[ChainId] = Encoder[Byte].contramap[ChainId](_.value)
      implicit val blockHashEncoder: Encoder[BlockHash] =
        Encoder[String].contramap[BlockHash](blockHash => Hex.toHexString(blockHash.byteString))
      implicit val thresholdEncoder: Encoder[Threshold] = io.circe.generic.semiauto.deriveEncoder
      io.circe.generic.semiauto.deriveEncoder
    }
  }

  def apply(): Opts[IO[ExitCode]] =
    opts.map { configFile =>
      EitherT(buildOutput(configFile)).foldF(
        // scalastyle:off regex
        IO.println(_).as(ExitCode.Error),
        output => IO.println(Encoder[Output].apply(output)).as(ExitCode.Success)
        // scalastyle:on regex
      )
    }

  private def buildOutput(configFile: Path): IO[Either[String, Output]] = {
    val rawBlockchainConfig = ConfigFactory.parseFile(configFile.toFile).resolve()
    val blockchainConfig = rawBlockchainConfig.getStringOpt("chain-mode") match {
      case Some("sidechain")  => SidechainBlockchainConfig.fromRawConfig(rawBlockchainConfig)
      case Some("standalone") => StandaloneBlockchainConfig.fromRawConfig(rawBlockchainConfig)
      case _                  => throw new Exception("'chain-mode' config missing. It has to be 'standalone' or 'sidechain'")
    }
    for {
      genesisHash <- ObftGenesisLoader.getGenesisHash[IO](blockchainConfig.genesisData)
    } yield blockchainConfig match {
      case sidechain: SidechainBlockchainConfig =>
        val params = sidechain.sidechainParams
        Right(
          Output(
            chainId = params.chainId,
            genesisHash = genesisHash,
            genesisMint = sidechain.initializationConfig.genesisMintUtxo,
            genesisUtxo = params.genesisUtxo,
            threshold = Threshold(
              numerator = params.thresholdNumerator,
              denominator = params.thresholdDenominator
            )
          )
        )
      case _ => Left("the configuration is not for a sidechain node")
    }
  }
}
