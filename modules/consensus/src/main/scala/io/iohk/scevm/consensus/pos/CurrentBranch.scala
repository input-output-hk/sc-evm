package io.iohk.scevm.consensus.pos

import cats.Functor
import cats.implicits.toFunctorOps
import io.iohk.scevm.domain.{BlockHash, ObftHeader}

/** Represent best branch info that is 'current' at a point time
  *
  * @param stable stable block header
  * @param best best block header
  */
final case class CurrentBranch(stable: ObftHeader, best: ObftHeader) {
  lazy val stableHash = stable.hash
  lazy val bestHash   = best.hash

  def update(betterBranch: ConsensusService.BetterBranch): CurrentBranch =
    CurrentBranch(
      stable = betterBranch.newStable.getOrElse(stable),
      best = betterBranch.newBest
    )
}

object CurrentBranch {
  def apply(header: ObftHeader): CurrentBranch =
    CurrentBranch(header, header)

  type Signal[F[_]] = fs2.concurrent.Signal[F, CurrentBranch]

  def get[F[_]](implicit signal: Signal[F]): F[CurrentBranch] = signal.get
  def best[F[_]: Functor: Signal]: F[ObftHeader]              = get.map(_.best)
  def bestHash[F[_]: Functor: Signal]: F[BlockHash]           = get.map(_.bestHash)
  def stable[F[_]: Functor: Signal]: F[ObftHeader]            = get.map(_.stable)
  def stableHash[F[_]: Functor: Signal]: F[BlockHash]         = get.map(_.stableHash)
}
