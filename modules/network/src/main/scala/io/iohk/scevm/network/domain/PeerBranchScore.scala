package io.iohk.scevm.network.domain

import cats.Show
import cats.syntax.all._
import io.iohk.scevm.domain.ObftHeader

sealed trait PeerBranchScore {
  val stable: ObftHeader
}

final case class StableHeaderScore private (stable: ObftHeader, score: Double) extends PeerBranchScore {
  override def toString: String =
    show"StableHeaderScore(stable=$stable, score=$score)"
}

final case class ChainTooShort private (stable: ObftHeader) extends PeerBranchScore {
  override def toString: String =
    show"ChainTooShort(stable=$stable)"
}

object PeerBranchScore {
  implicit val show: Show[PeerBranchScore] = Show.fromToString

  def from(
      stable: ObftHeader,
      ancestorOpt: Option[ObftHeader]
  ): PeerBranchScore =
    ancestorOpt
      .map { ancestor =>
        StableHeaderScore(stable, ChainDensity.density(stable, ancestor))
      }
      .getOrElse(ChainTooShort(stable))

}
