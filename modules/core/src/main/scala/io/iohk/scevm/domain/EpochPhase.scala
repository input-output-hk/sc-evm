package io.iohk.scevm.domain

import cats.Show

sealed abstract class EpochPhase(val raw: String) extends Product

object EpochPhase {
  final case object Regular                extends EpochPhase("regular")
  final case object ClosedTransactionBatch extends EpochPhase("closedTransactionBatch")
  final case object Handover               extends EpochPhase("handover")

  def fromRaw: PartialFunction[String, EpochPhase] = {
    case "regular"                => Regular
    case "closedTransactionBatch" => ClosedTransactionBatch
    case "handover"               => Handover
  }

  implicit val show: Show[EpochPhase] = Show.fromToString
}
