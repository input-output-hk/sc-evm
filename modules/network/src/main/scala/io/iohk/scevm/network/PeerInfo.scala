package io.iohk.scevm.network

import cats.Show
import io.iohk.scevm.network.domain.PeerBranchScore

final case class PeerInfo(
    peerBranchScore: Option[PeerBranchScore]
) {

  def withPeerBranchScore(peerBranchScore: PeerBranchScore): PeerInfo =
    copy(peerBranchScore = Some(peerBranchScore))
}

object PeerInfo {
  def empty: PeerInfo =
    PeerInfo(None)

  implicit val show: Show[PeerInfo] = cats.derived.semiauto.show
}
