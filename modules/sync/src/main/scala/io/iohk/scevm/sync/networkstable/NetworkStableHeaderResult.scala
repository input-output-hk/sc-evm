package io.iohk.scevm.sync.networkstable

import cats.data.NonEmptyList
import io.iohk.scevm.domain.ObftHeader
import io.iohk.scevm.network.PeerId

sealed trait NetworkStableHeaderResult
sealed trait NetworkStableHeaderError   extends NetworkStableHeaderResult
sealed trait NetworkStableHeaderSuccess extends NetworkStableHeaderResult

object NetworkStableHeaderResult {
  final case class StableHeaderFound(stableHeader: ObftHeader, score: Double, peerIdCandidates: NonEmptyList[PeerId])
      extends NetworkStableHeaderSuccess
  final case class StableHeaderNotFound(minimumScore: Double) extends NetworkStableHeaderError
  final case object NoAvailablePeer                           extends NetworkStableHeaderError
  final case object NetworkChainTooShort                      extends NetworkStableHeaderError
}
