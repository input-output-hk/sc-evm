package io.iohk.scevm.ledger

import cats.syntax.all._
import cats.{Applicative, Show}
import io.iohk.scevm.domain.{SidechainPublicKey, Slot}

import LeaderElection.ElectionFailure

trait LeaderElection[F[_]] {
  def getSlotLeader(slot: Slot): F[Either[ElectionFailure, SidechainPublicKey]]
}

class RoundRobinLeaderElection[F[_]: Applicative](validators: IndexedSeq[SidechainPublicKey])
    extends LeaderElection[F] {
  override def getSlotLeader(slot: Slot): F[Either[ElectionFailure, SidechainPublicKey]] =
    if (validators.isEmpty) ElectionFailure.notEnoughCandidates.asLeft[SidechainPublicKey].pure[F]
    else {
      val roundSize = validators.length
      val round     = (slot.number % roundSize).toInt
      validators(round).asRight[ElectionFailure].pure[F]
    }
}

object LeaderElection {
  sealed trait ElectionFailure

  final case class DataNotAvailable(mainchainEpoch: Long, sidechainEpoch: Long) extends ElectionFailure {
    override def toString: String =
      s"Currently no data is available on the main chain for this epoch (mainchain = $mainchainEpoch, sidechain = $sidechainEpoch)"
  }
  object DataNotAvailable {
    implicit val show: Show[DataNotAvailable] = Show.fromToString
  }
  final case class RequirementsNotMet(additionalInfo: String) extends ElectionFailure {
    override def toString: String =
      s"Some requirements to be able to choose the leader are not met ($additionalInfo)."
  }

  object ElectionFailure {
    def requirementsNotMeet(additionalData: String): ElectionFailure = RequirementsNotMet(additionalData)
    val notEnoughCandidates: ElectionFailure                         = RequirementsNotMet("Not enough candidates")
    def dataNotAvailable(mainchainEpoch: Long, sidechainEpoch: Long): ElectionFailure =
      DataNotAvailable(mainchainEpoch, sidechainEpoch)

    implicit val show: Show[ElectionFailure] = cats.derived.semiauto.show

  }
}
