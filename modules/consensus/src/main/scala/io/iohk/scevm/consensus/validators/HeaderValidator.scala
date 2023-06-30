package io.iohk.scevm.consensus.validators

import cats.data.EitherT
import cats.syntax.all._
import cats.{Monad, Show}
import io.iohk.scevm.domain.{Address, ObftHeader, SidechainPublicKey, Slot}
import io.iohk.scevm.ledger.{LeaderElection, SlotDerivation}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}

import HeaderValidator._

trait HeaderValidator[F[_]] {
  def validate(header: ObftHeader): F[Either[HeaderError, ObftHeader]]
}

class HeaderValidatorImpl[F[_]: Monad: LoggerFactory](
    leaderElection: LeaderElection[F],
    genesisHeader: ObftHeader,
    slotDerivation: SlotDerivation
) extends HeaderValidator[F] {
  private val logger: SelfAwareStructuredLogger[F] = LoggerFactory[F].getLogger

  override def validate(header: ObftHeader): F[Either[HeaderError, ObftHeader]] =
    if (header.number == genesisHeader.number)
      validateGenesis(header).pure[F]
    else
      EitherT(leaderElection.getSlotLeader(header.slotNumber))
        .leftSemiflatMap(error =>
          logger.error(show"$error") >>
            HeaderNotLeader(header).pure
        )
        .subflatMap { slotLeader =>
          for {
            _ <- validateLeaderSlot(header, slotLeader)
            _ <- validateBeneficiary(header, slotLeader)
            _ <- validateSignature(header)
            _ <- validateGasLimit(header)
            _ <- validateGasUsed(header)
            _ <- validateMaxGasLimit(header)
            _ <- validateSlotAgainstTimestamp(t => slotDerivation.getSlot(t).left.map(_ => NoTimestampError(header)))(
                   header
                 )
          } yield header
        }
        .value

  /* Private methods */

  private def validateGenesis(header: ObftHeader): Either[HeaderError, ObftHeader] =
    if (header == genesisHeader) Right(header) else Left(HeaderDifferentGenesis(header))

  /** Validates that the declared public key is the leader's public key */
  private def validateLeaderSlot(
      header: ObftHeader,
      slotLeader: SidechainPublicKey
  ): Either[HeaderError, ObftHeader] =
    Either.cond(header.publicSigningKey == slotLeader, header, HeaderNotLeader(header))

  private def validateBeneficiary(
      header: ObftHeader,
      slotLeader: SidechainPublicKey
  ): Either[HeaderError, ObftHeader] =
    Either.cond(
      header.beneficiary == Address.fromPublicKey(slotLeader),
      header,
      BeneficiaryNotLeader(header)
    )

  /** Validates that the block was signed by the declared public key */
  private def validateSignature(
      header: ObftHeader
  ): Either[HeaderError, ObftHeader] =
    header.signature.publicKey(header.hashWithoutSignature) match {
      case Some(signaturePubKey) if signaturePubKey == header.publicSigningKey.bytes => Right(header)
      case _                                                                         => Left(HeaderInvalidSignature(header))
    }

  private[validators] def validateGasLimit(header: ObftHeader): Either[GasLimitError, ObftHeader] =
    Either.cond(header.gasLimit >= 0, header, GasLimitError(header))

  private[validators] def validateGasUsed(header: ObftHeader): Either[GasUsedError, ObftHeader] =
    Either.cond(
      header.gasUsed >= 0 && header.gasUsed <= header.gasLimit,
      header,
      GasUsedError(header)
    )

  private[validators] def validateMaxGasLimit(
      header: ObftHeader,
      maxGasLimit: Long = MaxGasLimit
  ): Either[MaxGasLimitError, ObftHeader] =
    Either.cond(header.gasLimit <= maxGasLimit, header, MaxGasLimitError(header))

  private[validators] def validateSlotAgainstTimestamp(
      timestampToSlotNumber: UnixTimestamp => Either[NoTimestampError, Slot]
  )(
      header: ObftHeader
  ): Either[HeaderError, ObftHeader] =
    for {
      slotNumber <- timestampToSlotNumber(header.unixTimestamp)
      _          <- Either.cond(header.slotNumber == slotNumber, header, SlotNumberError(header))
    } yield header
}

object HeaderValidator {
  def apply[F[_]](implicit ev: HeaderValidator[F]): HeaderValidator[F] = ev

  val MaxGasLimit: Long = Long.MaxValue // max gasLimit is equal 2^63-1 according to EIP106

  sealed trait HeaderError {
    def header: ObftHeader
  }

  object HeaderError {
    implicit val show: Show[HeaderError] = cats.derived.semiauto.show

    def userFriendlyMessage(error: HeaderError): String = error match {
      case HeaderInvalidSignature(_)      => "The signature is invalid for the header."
      case HeaderNotLeader(_)             => "The block producer does not match the expected leader for that slot."
      case BeneficiaryNotLeader(_)        => "The beneficiary field of the header should be the block producer."
      case HeaderDifferentGenesis(header) => s"The received genesis block (${header.idTag}) is different than expected."
      case GasLimitError(header)          => s"The gas limit (${header.gasLimit}) is invalid"
      case GasUsedError(header)           => s"The gas used (${header.gasUsed}) is invalid"
      case MaxGasLimitError(header)       => s"The gas limit(${header.gasLimit}) is higher than the maximum gas limit"
      case SlotNumberError(header) =>
        s"The slot number (${header.slotNumber}) does not match the timestamp (${header.unixTimestamp})"
      case NoTimestampError(header) => s"The timestamp (${header.unixTimestamp}) does not match a slot"
    }
  }

  final case class HeaderInvalidSignature(header: ObftHeader) extends HeaderError
  final case class HeaderNotLeader(header: ObftHeader)        extends HeaderError
  object HeaderNotLeader {
    implicit val show: Show[HeaderNotLeader] =
      Show.show(e => show"HeaderNotLeader(header=${e.header}, key=${e.header.publicSigningKey}")
  }
  final case class BeneficiaryNotLeader(header: ObftHeader)   extends HeaderError
  final case class HeaderDifferentGenesis(header: ObftHeader) extends HeaderError

  final case class GasLimitError(header: ObftHeader)    extends HeaderError
  final case class GasUsedError(header: ObftHeader)     extends HeaderError
  final case class MaxGasLimitError(header: ObftHeader) extends HeaderError
  final case class SlotNumberError(header: ObftHeader)  extends HeaderError
  final case class NoTimestampError(header: ObftHeader) extends HeaderError
}
