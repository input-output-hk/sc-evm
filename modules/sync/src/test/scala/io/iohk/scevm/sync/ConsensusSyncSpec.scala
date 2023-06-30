package io.iohk.scevm.sync

import cats.data.NonEmptyList
import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref, Temporal}
import fs2.concurrent.Signal
import io.iohk.scevm.db.storage.BranchProvider.MissingParent
import io.iohk.scevm.domain.ObftHeader
import io.iohk.scevm.network.{BranchFetcher, PeerId}
import io.iohk.scevm.testing.BlockGenerators.{obftBlockChainGen, obftEmptyBodyBlockGen}
import io.iohk.scevm.testing._
import org.scalacheck.Gen
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.duration.DurationInt

class ConsensusSyncSpec
    extends AnyWordSpec
    with IOSupport
    with ScalaFutures
    with NormalPatience
    with ScalaCheckPropertyChecks
    with MockFactory
    with Matchers {

  "should handle MissingParent consensus result by downloading the missing blocks" in {
    forAll {
      for {
        chainLength               <- Gen.choose(10, 100)
        chain                     <- obftBlockChainGen(chainLength, fixtures.GenesisBlock.block, obftEmptyBodyBlockGen)
        (stablePart, unstablePart) = chain.splitAt(chainLength / 2)
        stableBlock                = stablePart.last
        // forked at stable with the same length as the unstable part
        forkedChain <- obftBlockChainGen(unstablePart.length, stableBlock, obftEmptyBodyBlockGen)
        // tip of the forked chain is a 'new block'
        newBlock = forkedChain.last
      } yield (stableBlock, newBlock)
    } { case (stableBlock, newBlock) =>
      val testProgram = for {
        observedBranchFetcherCalls <- Ref[IO].of(List.empty[(ObftHeader, ObftHeader)])
        peersRef                    = Signal.constant[IO, List[PeerId]](List(PeerId("peer1")))
        mockBranchFetcher = new BranchFetcher[IO] {
                              override def fetchBranch(
                                  from: ObftHeader,
                                  to: ObftHeader,
                                  peerIds: NonEmptyList[PeerId]
                              ): IO[BranchFetcher.BranchFetcherResult] =
                                observedBranchFetcherCalls
                                  .update(_ :+ (from, to))
                                  .as(
                                    BranchFetcher.BranchFetcherResult.Connected(from, to)
                                  )
                            }

        consensusSyncUsageProgram = ConsensusSyncImpl[IO](
                                      mockBranchFetcher,
                                      peersRef
                                    ).use { consensusSync =>
                                      consensusSync.onMissingParent(
                                        stableBlock.header,
                                        MissingParent(
                                          newBlock.header.parentHash,
                                          newBlock.header.number.previous,
                                          newBlock.header
                                        )
                                      ) >> Temporal[IO].sleep(500.millis)
                                    }

        control <- TestControl.execute(consensusSyncUsageProgram)
        _       <- control.advanceAndTick(1.second)

        branchFetcherCalls <- observedBranchFetcherCalls.get
      } yield assert(branchFetcherCalls == List((stableBlock.header, newBlock.header)))

      testProgram.ioValue
    }
  }
}
