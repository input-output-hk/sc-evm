package io.iohk.scevm.rpc.sidechain

import cats.syntax.all._
import io.iohk.scevm.cardanofollower.datasource.ValidLeaderCandidate
import io.iohk.scevm.rpc.sidechain.SidechainController.CandidateRegistrationStatus
import io.iohk.scevm.sidechain.SidechainEpoch
import io.iohk.scevm.sidechain.transactions.OutgoingTxId
import io.iohk.scevm.trustlesssidechain.cardano.{UtxoId, UtxoInfo}
import org.json4s.{CustomSerializer, FieldSerializer, Formats, JInt, JString}

object SidechainJsonSerializers {

  lazy val All: Formats => Formats =
    _ +
      ValidLeaderCandidateSerializer +
      UtxoInfoResponseSerializer +
      UtxoIdSerializer +
      RegistrationStatusSerializer +
      SidechainEpochParamSerializer +
      OutgoingTxIdSerializer

  val ValidLeaderCandidateSerializer: FieldSerializer[ValidLeaderCandidate[_]] =
    FieldSerializer[ValidLeaderCandidate[_]] {
      case ("isPending", _) => None
      case other            => Some(other)
    }

  val UtxoInfoResponseSerializer: FieldSerializer[UtxoInfo] =
    FieldSerializer[UtxoInfo] {
      case ("blockNumber", value) => Some(("mainchainBlockNumber", value))
      case ("slotNumber", value)  => Some(("mainchainSlotNumber", value))
      case other                  => Some(other)
    }

  object UtxoIdSerializer
      extends CustomSerializer[UtxoId](_ => (PartialFunction.empty, { case utxo: UtxoId => JString(show"$utxo") }))

  object RegistrationStatusSerializer
      extends CustomSerializer[CandidateRegistrationStatus](_ =>
        (
          {
            case JString("Invalid")               => CandidateRegistrationStatus.Invalid
            case JString("Pending")               => CandidateRegistrationStatus.Pending
            case JString("Active")                => CandidateRegistrationStatus.Active
            case JString("Superseded")            => CandidateRegistrationStatus.Superseded
            case JString("Deregistered")          => CandidateRegistrationStatus.Deregistered
            case JString("PendingDeregistration") => CandidateRegistrationStatus.PendingDeregistration
          },
          { case registrationStatus: CandidateRegistrationStatus =>
            registrationStatus match {
              case CandidateRegistrationStatus.Invalid               => JString("Invalid")
              case CandidateRegistrationStatus.Pending               => JString("Pending")
              case CandidateRegistrationStatus.Active                => JString("Active")
              case CandidateRegistrationStatus.Superseded            => JString("Superseded")
              case CandidateRegistrationStatus.Deregistered          => JString("Deregistered")
              case CandidateRegistrationStatus.PendingDeregistration => JString("PendingDeregistration")
            }
          }
        )
      )

  object SidechainEpochParamSerializer
      extends CustomSerializer[SidechainEpochParam](_ =>
        (
          {
            case JInt(n)           => SidechainEpochParam.ByEpoch(SidechainEpoch(n.toLong))
            case JString("latest") => SidechainEpochParam.Latest
          },
          {
            case SidechainEpochParam.Latest         => JString("latest")
            case SidechainEpochParam.ByEpoch(epoch) => JInt(epoch.number)
          }
        )
      )

  object OutgoingTxIdSerializer
      extends CustomSerializer[OutgoingTxId](_ =>
        (
          { case JInt(n) =>
            OutgoingTxId(n.toLong)
          },
          { case n: Long =>
            JInt(n)
          }
        )
      )
}
