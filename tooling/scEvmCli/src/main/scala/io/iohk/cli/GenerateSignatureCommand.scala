package io.iohk.cli

import cats.data.Validated
import cats.effect.{ExitCode, IO}
import cats.syntax.all._
import com.monovore.decline.Opts
import io.circe.Encoder
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{ECDSA, EdDSA}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.BlockHash
import io.iohk.scevm.trustlesssidechain.cardano.{Blake2bHash32, UtxoId}
import io.iohk.scevm.trustlesssidechain.{RegistrationMessage, SidechainParams}

import scala.util.Try

object GenerateSignatureCommand {
  final private case class Params(
      genesisHash: BlockHash,
      chainId: ChainId,
      registrationUtxo: UtxoId,
      spoPrivateKey: EdDSA.PrivateKey,
      validatorPrivateKey: ECDSA.PrivateKey,
      genesisCommitteeHashUtxo: UtxoId,
      thresholdNumerator: Int,
      thresholdDenominator: Int
  )

  final private case class Output(
      spoPublicKey: ByteString,
      spoSignature: ByteString,
      sidechainPublicKey: ByteString,
      sidechainSignature: ByteString
  )
  private object Output {
    implicit val encoder: Encoder[Output] = {
      implicit val byteStringEncoder: Encoder[ByteString] = Encoder[String].contramap[ByteString](Hex.toHexString)
      io.circe.generic.semiauto.deriveEncoder
    }
  }

  private val genesisHash: Opts[BlockHash] = Opts
    .option[String]("genesis-hash", "Hash of the sidechain genesis block")
    .mapValidated(str => Validated.fromEither(Hex.decode(str).map(BlockHash.apply)).toValidatedNel)

  private val chainId: Opts[ChainId] = Opts
    .option[Either[String, Int]]("sidechain-id", "Sidechain chain id")
    .mapValidated {
      case Left(str) =>
        Validated.fromEither(Try(Hex.parseHexNumberUnsafe(str)).toEither.left.map(_.getMessage)).toValidatedNel
      case Right(value) => Validated.fromEither(BigInt(value).asRight[String]).toValidatedNel
    }
    .mapValidated(bn => Validated.fromEither(ChainId.from(bn)).toValidatedNel)

  private val spoPrivateKey = Opts
    .option[String]("spo-signing-key", "SPO signing key")
    .mapValidated(str => Validated.fromEither(EdDSA.PrivateKey.fromHex(str)).toValidatedNel)

  private val sidechainPrivateKey = Opts
    .option[String]("sidechain-signing-key", "Sidechain signing key")
    .mapValidated(str => Validated.fromEither(ECDSA.PrivateKey.fromHex(str)).toValidatedNel)

  private val registrationUtxo = Opts
    .option[String]("registration-utxo", "Utxo to be consumed during the registration")
    .mapValidated { str =>
      Validated.fromEither(UtxoId.parse(str)).toValidatedNel
    }

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

  private val opts: Opts[Params] = Opts.subcommand("generate-signature", "Compute registration signature") {
    (
      genesisHash,
      chainId,
      registrationUtxo,
      spoPrivateKey,
      sidechainPrivateKey,
      genesisUtxo,
      thresholdNumerator,
      thresholdDenominator
    ).mapN(Params)
  }

  def apply(): Opts[IO[ExitCode]] =
    opts.map {
      case Params(
            genesisHash,
            chainId,
            registrationUtxo,
            spoPrivateKey,
            scPrivateKey,
            genesisUtxo,
            thresholdNumerator,
            thresholdDenominator
          ) =>
        val msg = RegistrationMessage.createEncoded(
          ECDSA.PublicKey.fromPrivateKey(scPrivateKey),
          registrationUtxo,
          SidechainParams(chainId, genesisHash, genesisUtxo, thresholdNumerator, thresholdDenominator)
        )
        // scalastyle:off regex
        IO.println(
          Encoder[Output].apply(
            Output(
              spoPublicKey = EdDSA.PublicKey.fromPrivate(spoPrivateKey).bytes,
              spoSignature = spoPrivateKey.sign(msg).bytes,
              sidechainPublicKey = ECDSA.PublicKey.fromPrivateKey(scPrivateKey).compressedBytes,
              sidechainSignature = scPrivateKey.sign(Blake2bHash32.hash(msg).bytes).withoutRecoveryByte.toBytes
            )
          )
        ).as(ExitCode.Success)
      // scalastyle:on regex
    }
}
