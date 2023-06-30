package io.iohk.scevm.sidechain

import cats.Applicative
import cats.implicits.{showInterpolator, toFunctorOps}
import fs2.concurrent.Signal
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.domain.{BlockNumber, EpochPhase, Tick}
import org.typelevel.log4cats.{LoggerFactory, SelfAwareStructuredLogger}

object PreventStartInHandoverTicker {

  def apply[F[_]: Applicative: LoggerFactory](
      ticker: fs2.Stream[F, Tick],
      currentBranch: Signal[F, CurrentBranch],
      epochDerivation: SidechainEpochDerivation[F]
  ): fs2.Stream[F, Tick] = {
    val log: SelfAwareStructuredLogger[F] = LoggerFactory[F].getLogger

    def filter(tick: Tick, currentBranch: CurrentBranch): F[Boolean] = {
      val isHandover  = epochDerivation.getSidechainEpochPhase(tick.slot) == EpochPhase.Handover
      val isAtGenesis = currentBranch.best.number == BlockNumber(0)
      if (isHandover && isAtGenesis)
        log
          .info(
            show"Can't produce nor validate block in slot: ${tick.slot} because chain can't start in Handover phase"
          )
          .as(false)
      else
        Applicative[F].pure(true)
    }

    ticker.zip(currentBranch.continuous).evalFilter((filter _).tupled).map(_._1)
  }
}
