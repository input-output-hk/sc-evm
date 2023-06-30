package io.iohk.scevm.network

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.kernel.Ref
import cats.implicits.{catsSyntaxApplicativeId, toTraverseOps}
import io.iohk.scevm.db.storage.BranchProvider
import io.iohk.scevm.domain.{BlockHash, ObftBlock, ObftHeader}
import io.iohk.scevm.network.ReceiptsFetcher.ReceiptsFetchError
import io.iohk.scevm.testing.BlockGenerators.{obftBlockChainGen, obftEmptyBodyBlockGen, obftSmallBlockGen}
import io.iohk.scevm.testing.{IOSupport, NormalPatience, fixtures}
import org.scalacheck.{Gen, Shrink}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class BranchWithReceiptsFetcherSpec
    extends AnyWordSpec
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with NormalPatience
    with IOSupport {

  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  "BranchWithReceiptsFetcher" should {
    "download all blocks and receipts from the given range" in {
      forAll {
        for {
          chainLength           <- Gen.choose(2, 100)
          chain                 <- obftBlockChainGen(chainLength, fixtures.GenesisBlock.block, obftSmallBlockGen)
          fromIndex             <- Gen.choose(0, chainLength - 2)
          toIndex               <- Gen.choose(fromIndex + 1, chainLength - 1)
          branch                 = chain.slice(fromIndex, toIndex + 1)
          (fromHeader, toHeader) = (branch.head.header, branch.last.header)
        } yield (chain, branch, fromHeader, toHeader)
      } { case (_, branch, from, to) =>
        val test = for {
          downloadedReceiptsRef <- Ref.of[IO, List[BlockHash]](List.empty)

          mockStorage <- Ref.of[IO, List[ObftBlock]](List.empty)

          mockBranchFetcher = new BranchFetcher[IO] {
                                override def fetchBranch(
                                    from: ObftHeader,
                                    to: ObftHeader,
                                    peerIds: NonEmptyList[PeerId]
                                ): IO[BranchFetcher.BranchFetcherResult] =
                                  if ((branch.head.header, branch.last.header) == (from, to)) {
                                    branch
                                      .traverse { block =>
                                        mockStorage.update(_ :+ block).void
                                      }
                                      .as(BranchFetcher.BranchFetcherResult.Connected(from, to))
                                  } else
                                    BranchFetcher.BranchFetcherResult
                                      .Disconnected(branch.head.header, from, to)
                                      .pure[IO]
                              }

          mockBranchProvider = new BranchProvider[IO] {
                                 override def getChildren(parent: BlockHash): IO[Seq[ObftHeader]]      = ???
                                 override def findSuffixesTips(parent: BlockHash): IO[Seq[ObftHeader]] = ???
                                 override def fetchChain(
                                     from: ObftHeader,
                                     to: ObftHeader
                                 ): IO[Either[BranchProvider.BranchRetrievalError, List[ObftHeader]]] =
                                   mockStorage.get.map { storage =>
                                     Right(storage.map(_.header))
                                   }
                               }

          mockReceiptsFetcher = new ReceiptsFetcher[IO] {
                                  override def fetchReceipts(
                                      headers: Seq[ObftHeader],
                                      peerIds: NonEmptyList[PeerId]
                                  ): IO[Either[ReceiptsFetcher.ReceiptsFetchError, Unit]] =
                                    downloadedReceiptsRef.update(_ ++ headers.map(_.hash)).void.as(Right(()))
                                }

          branchWithReceiptsFetcher = new BranchWithReceiptsFetcher[IO](
                                        mockBranchFetcher,
                                        mockReceiptsFetcher,
                                        mockBranchProvider
                                      )

          result <- branchWithReceiptsFetcher.fetchBranch(from, to, NonEmptyList.one(PeerId("peer1")))

          downloadedReceipts <- downloadedReceiptsRef.get
          downloadedBlocks   <- mockStorage.get
        } yield {
          assert(result == BranchFetcher.BranchFetcherResult.Connected(from, to))
          val expectedDownloadedReceipts =
            branch.flatMap(block => if (block.header.containsTransactions) Some(block.hash) else None)
          assert(downloadedReceipts == expectedDownloadedReceipts)
          assert(downloadedBlocks == branch)
        }

        test.ioValue
      }
    }

    "return RecoverableError if the receipts cannot be downloaded due to unavailable peers" in {
      forAll {
        for {
          chainLength           <- Gen.choose(2, 100)
          chain                 <- obftBlockChainGen(chainLength, fixtures.GenesisBlock.block, obftSmallBlockGen)
          fromIndex             <- Gen.choose(0, chainLength - 2)
          toIndex               <- Gen.choose(fromIndex + 1, chainLength - 1)
          branch                 = chain.slice(fromIndex, toIndex + 1)
          (fromHeader, toHeader) = (branch.head.header, branch.last.header)
        } yield (chain, branch, fromHeader, toHeader)
      } { case (_, branch, from, to) =>
        val test = for {
          mockStorage <- Ref.of[IO, List[ObftBlock]](List.empty)

          mockBranchFetcher = new BranchFetcher[IO] {
                                override def fetchBranch(
                                    from: ObftHeader,
                                    to: ObftHeader,
                                    peerIds: NonEmptyList[PeerId]
                                ): IO[BranchFetcher.BranchFetcherResult] = branch
                                  .traverse { block =>
                                    mockStorage.update(_ :+ block).void
                                  }
                                  .as(BranchFetcher.BranchFetcherResult.Connected(from, to))
                              }

          mockBranchProvider = new BranchProvider[IO] {
                                 override def getChildren(parent: BlockHash): IO[Seq[ObftHeader]]      = ???
                                 override def findSuffixesTips(parent: BlockHash): IO[Seq[ObftHeader]] = ???
                                 override def fetchChain(
                                     from: ObftHeader,
                                     to: ObftHeader
                                 ): IO[Either[BranchProvider.BranchRetrievalError, List[ObftHeader]]] =
                                   mockStorage.get.map { storage =>
                                     Right(storage.map(_.header))
                                   }
                               }

          mockReceiptsFetcher = new ReceiptsFetcher[IO] {
                                  override def fetchReceipts(
                                      headers: Seq[ObftHeader],
                                      peerIds: NonEmptyList[PeerId]
                                  ): IO[Either[ReceiptsFetcher.ReceiptsFetchError, Unit]] =
                                    IO.pure(Left(ReceiptsFetcher.ReceiptsFetchError.AllPeersTimedOut))
                                }

          branchWithReceiptsFetcher = new BranchWithReceiptsFetcher[IO](
                                        mockBranchFetcher,
                                        mockReceiptsFetcher,
                                        mockBranchProvider
                                      )

          result <- branchWithReceiptsFetcher.fetchBranch(from, to, NonEmptyList.one(PeerId("peer1")))

          downloadedBlocks <- mockStorage.get
        } yield {
          assert(
            result == BranchFetcher.BranchFetcherResult.RecoverableError(
              ReceiptsFetchError.userFriendlyMessage(ReceiptsFetcher.ReceiptsFetchError.AllPeersTimedOut)
            )
          )
          assert(downloadedBlocks == branch)
        }

        test.ioValue
      }
    }

    "not request receipts for blocks without transactions" in {
      forAll {
        for {
          chainLength           <- Gen.choose(2, 100)
          chain                 <- obftBlockChainGen(chainLength, fixtures.GenesisBlock.block, obftEmptyBodyBlockGen)
          fromIndex             <- Gen.choose(0, chainLength - 2)
          toIndex               <- Gen.choose(fromIndex + 1, chainLength - 1)
          branch                 = chain.slice(fromIndex, toIndex + 1)
          (fromHeader, toHeader) = (branch.head.header, branch.last.header)
        } yield (chain, branch, fromHeader, toHeader)
      } { case (_, branch, from, to) =>
        val test = for {
          downloadedReceiptsRef <- Ref.of[IO, List[BlockHash]](List.empty)

          mockStorage <- Ref.of[IO, List[ObftBlock]](List.empty)

          mockBranchFetcher = new BranchFetcher[IO] {
                                override def fetchBranch(
                                    from: ObftHeader,
                                    to: ObftHeader,
                                    peerIds: NonEmptyList[PeerId]
                                ): IO[BranchFetcher.BranchFetcherResult] = branch
                                  .traverse { block =>
                                    mockStorage.update(_ :+ block).void
                                  }
                                  .as(BranchFetcher.BranchFetcherResult.Connected(from, to))
                              }

          mockBranchProvider = new BranchProvider[IO] {
                                 override def getChildren(parent: BlockHash): IO[Seq[ObftHeader]] = ???

                                 override def findSuffixesTips(parent: BlockHash): IO[Seq[ObftHeader]] = ???

                                 override def fetchChain(
                                     from: ObftHeader,
                                     to: ObftHeader
                                 ): IO[Either[BranchProvider.BranchRetrievalError, List[ObftHeader]]] =
                                   mockStorage.get.map { storage =>
                                     Right(storage.map(_.header))
                                   }
                               }

          mockReceiptsFetcher = new ReceiptsFetcher[IO] {
                                  override def fetchReceipts(
                                      headers: Seq[ObftHeader],
                                      peerIds: NonEmptyList[PeerId]
                                  ): IO[Either[ReceiptsFetcher.ReceiptsFetchError, Unit]] =
                                    downloadedReceiptsRef.update(_ ++ headers.map(_.hash)).void.as(Right(()))
                                }

          branchWithReceiptsFetcher = new BranchWithReceiptsFetcher[IO](
                                        mockBranchFetcher,
                                        mockReceiptsFetcher,
                                        mockBranchProvider
                                      )

          result <- branchWithReceiptsFetcher.fetchBranch(from, to, NonEmptyList.one(PeerId("peer1")))

          downloadedReceipts <- downloadedReceiptsRef.get
          downloadedBlocks   <- mockStorage.get
        } yield {
          assert(result == BranchFetcher.BranchFetcherResult.Connected(from, to))
          assert(downloadedReceipts.isEmpty)
          assert(downloadedBlocks == branch)
        }

        test.ioValue
      }
    }
  }
}
