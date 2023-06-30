package io.iohk.cli

import cats.data.{NonEmptyList, Validated}
import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import com.monovore.decline.Opts
import io.circe.Encoder
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{BlockHash, SidechainPrivateKey, SidechainPublicKey}
import io.iohk.scevm.sidechain.SidechainEpoch
import io.iohk.scevm.sidechain.certificate.{CommitteeHandoverSigner, MerkleRootSigner}
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.trustlesssidechain.SidechainParams
import io.iohk.scevm.trustlesssidechain.cardano.UtxoId
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.slf4j.Slf4jFactory

import scala.util.Try

object GenerateEpochSignaturesCommand {
  implicit private val logging: LoggerFactory[IO] = Slf4jFactory[IO]

  final private case class Params(
      chainId: ChainId,
      genesisHash: BlockHash,
      genesisCommitteeHashUtxo: UtxoId,
      thresholdNumerator: Int,
      thresholdDenominator: Int,
      sidechainEpoch: SidechainEpoch,
      previousMerkleRoot: Option[RootHash],
      merkleRoot: Option[RootHash],
      committeeKeys: NonEmptyList[SidechainPublicKey],
      privateKey: SidechainPrivateKey
  )

  final private case class Output(
      publicKeyCompressed: ByteString,
      handoverSignature: ByteString,
      transactionsSignature: Option[ByteString]
  )
  private object Output {
    implicit val encoder: Encoder[Output] = {
      implicit val byteStringEncoder: Encoder[ByteString] = Encoder[String].contramap[ByteString](Hex.toHexString)
      io.circe.generic.semiauto.deriveEncoder
    }
  }

  private val chainId: Opts[ChainId] = Opts
    .option[Either[String, Int]]("sidechain-id", "Sidechain chain id")
    .mapValidated {
      case Left(str) =>
        Validated.fromEither(Try(Hex.parseHexNumberUnsafe(str)).toEither.left.map(_.getMessage)).toValidatedNel
      case Right(value) => Validated.fromEither(BigInt(value).asRight[String]).toValidatedNel
    }
    .mapValidated(bn => Validated.fromEither(ChainId.from(bn)).toValidatedNel)

  private val genesisHash: Opts[BlockHash] = Opts
    .option[String]("genesis-hash", "Hash of the sidechain genesis block")
    .mapValidated(str => Validated.fromEither(Hex.decode(str).map(BlockHash.apply)).toValidatedNel)

  private val genesisUtxo = Opts
    .option[String]("genesis-utxo", "Utxo that was used to initialize the sidechain")
    .mapValidated { str =>
      Validated.fromEither(UtxoId.parse(str)).toValidatedNel
    }

  private val thresholdNumerator =
    Opts.option[Int]("threshold-numerator", "Committee signatures threshold numerator").mapValidated { i =>
      Validated.condNel(i > 0, i, "Threshold numerator has to be positive")
    }

  private val thresholdDenominator =
    Opts.option[Int]("threshold-denominator", "Committee signatures threshold numerator").mapValidated { i =>
      Validated.condNel(i > 0, i, "Threshold denominator has to be positive")
    }

  private val sidechainEpoch =
    Opts.option[Long]("sidechain-epoch", "Epoch for which signatures are generated").mapValidated { i =>
      Validated.condNel(i > 0, i, "Epoch has to be positive").map(SidechainEpoch(_))
    }

  private val previousMerkleRoot = Opts
    .option[String](
      "previous-merkle-root",
      "previous Merkle root from 'outgoingTransactions' or from 'handoverSignatures'"
    )
    .mapValidated { str =>
      Validated.fromEither(Hex.decode(str).map(RootHash(_))).toValidatedNel
    }
    .orNone

  private val merkleRoot = Opts
    .option[String]("merkle-root", "Merkle root from 'outgoingTransactions' if present")
    .mapValidated { str =>
      Validated.fromEither(Hex.decode(str).map(RootHash(_))).toValidatedNel
    }
    .orNone

  private val committeeKeys: Opts[NonEmptyList[ECDSA.PublicKey]] = Opts
    .options[String]("new-committee-pub-key", "New committee public key, not compressed")
    .mapValidated { strs =>
      strs.traverse(str => Validated.fromEither(ECDSA.PublicKey.fromHex(str)).toValidatedNel)
    }

  private val privateKey = Opts
    .option[String]("private-key", "ECDSA private signing key")
    .mapValidated(str => Validated.fromEither(ECDSA.PrivateKey.fromHex(str)).toValidatedNel)

  private val opts: Opts[Params] = Opts.subcommand("generate-epoch-signatures", "Compute handover signatures ") {
    (
      chainId,
      genesisHash,
      genesisUtxo,
      thresholdNumerator,
      thresholdDenominator,
      sidechainEpoch,
      previousMerkleRoot,
      merkleRoot,
      committeeKeys,
      privateKey
    ).mapN(Params)
  }

  def apply(): Opts[IO[ExitCode]] =
    opts.map {
      case Params(
            chainId,
            genesisHash,
            genesisUtxo,
            thresholdNumerator,
            thresholdDenominator,
            sidechainEpoch,
            previousMerkleRoot,
            merkleRoot,
            committeeKeys,
            privateKey
          ) =>
        val scParams = SidechainParams(chainId, genesisHash, genesisUtxo, thresholdNumerator, thresholdDenominator)

        val txsSignature =
          merkleRoot.map(mr => MerkleRootSigner.messageHash(previousMerkleRoot, scParams, mr)).map(privateKey.sign)

        val handoverMsgSignature = new CommitteeHandoverSigner[IO]
          .messageHash(
            scParams,
            committeeKeys,
            merkleRoot.orElse(previousMerkleRoot),
            sidechainEpoch
          )
          .map(blake2bHash32 => privateKey.sign(blake2bHash32.bytes))

        val publicKeyCompressed = ECDSA.PublicKey.fromPrivateKey(privateKey).compressedBytes

        handoverMsgSignature
          .flatMap { handoverSignature =>
            // scalastyle:off regex
            IO.println(
              Encoder[Output].apply(
                Output(
                  publicKeyCompressed = publicKeyCompressed,
                  handoverSignature = handoverSignature.withoutRecoveryByte.toBytes,
                  transactionsSignature = txsSignature.map(_.withoutRecoveryByte.toBytes)
                )
              )
            )
            // scalastyle:on regex
          }
          .as(ExitCode.Success)
    }
}
