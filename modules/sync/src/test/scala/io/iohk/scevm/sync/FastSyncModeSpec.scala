package io.iohk.scevm.sync

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.implicits.{catsSyntaxApplicativeId, showInterpolator, toTraverseOps}
import io.iohk.scevm.consensus.domain.BlocksBranch
import io.iohk.scevm.consensus.pos.{BranchExecution, ObftBlockExecution}
import io.iohk.scevm.db.storage.BranchProvider
import io.iohk.scevm.domain.{BlockHash, ObftBlock, ObftHeader}
import io.iohk.scevm.mpt.Node.Hash
import io.iohk.scevm.network.PeerChannel.MultiplePeersAskFailure.AllPeersTimedOut
import io.iohk.scevm.network.{BranchFetcher, BranchWithReceiptsFetcher, MptNodeFetcher, PeerId, ReceiptsFetcher}
import io.iohk.scevm.sync.SyncMode.{EvmValidationByExecutionError, FastSyncFailure}
import io.iohk.scevm.testing.BlockGenerators.{obftBlockChainGen, obftEmptyBodyBlockGen, obftSmallBlockGen}
import io.iohk.scevm.testing.{Generators, IOSupport, NormalPatience, fixtures}
import org.scalacheck.{Gen, Shrink}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class FastSyncModeSpec
    extends AnyWordSpec
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with IOSupport
    with NormalPatience {

  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  "FastSyncMode.validateBranch" should {
    "request MPT nodes for the last block in the range" in {
      forAll {
        for {
          chainLength <- Gen.choose(2, 100)
          chain       <- obftBlockChainGen(chainLength, fixtures.GenesisBlock.block, obftEmptyBodyBlockGen)
          // obftBlockChainGen generates blocks with the same state root
          lastBlockStateRootHash <- Generators.byteStringOfLengthNGen(20)
          fromIndex              <- Gen.choose(0, chainLength - 2)
          toIndex                <- Gen.choose(fromIndex + 1, chainLength - 1)
          branch                  = chain.slice(fromIndex, toIndex + 1)
          (fromHeader, toHeader)  = (branch.head.header, branch.last.header.copy(stateRoot = lastBlockStateRootHash))
        } yield (fromHeader, toHeader)
      } { case (from, to) =>
        val test = for {
          requestedNodeHashRef <- Ref.of[IO, List[Hash]](List.empty)

          mockMptNodeFetcher = new MptNodeFetcher[IO] {
                                 override def fetch(
                                     nodeHashes: List[Hash],
                                     peerIds: NonEmptyList[PeerId]
                                 ): IO[Either[MptNodeFetcher.NodeFetchError, Unit]] =
                                   requestedNodeHashRef.update(_ ++ nodeHashes).as(Right(()))
                               }

          fastSyncMode <- FastSyncMode[IO](branchFetcherUnused, mockMptNodeFetcher, fullSyncUnused)

          _                   <- fastSyncMode.validateBranch(from, to, NonEmptyList.of(PeerId("peer1")))
          requestedNodeHashes <- requestedNodeHashRef.get
        } yield assert(requestedNodeHashes == List(to.stateRoot))

        test.ioValue
      }
    }

    "switch to 'FullSyncValidation' for all subsequent validation calls" in {
      forAll {
        for {
          chainLength <- Gen.choose(2, 100)
          chain       <- obftBlockChainGen(chainLength, fixtures.GenesisBlock.block, obftEmptyBodyBlockGen)
          // obftBlockChainGen generates blocks with the same state root
          lastBlockStateRootHash <- Generators.byteStringOfLengthNGen(20)
          fromIndex              <- Gen.choose(0, chainLength - 2)
          toIndex                <- Gen.choose(fromIndex + 1, chainLength - 1)
          branch                  = chain.slice(fromIndex, toIndex + 1)
          (fromHeader, toHeader)  = (branch.head.header, branch.last.header.copy(stateRoot = lastBlockStateRootHash))
        } yield (fromHeader, toHeader)
      } { case (from, to) =>
        val test = for {
          requestedNodeHashRef           <- Ref.of[IO, List[Hash]](List.empty)
          requestedFullSyncValidationRef <- Ref.of[IO, List[(ObftHeader, ObftHeader)]](List.empty)

          mockMptNodeFetcher = new MptNodeFetcher[IO] {
                                 override def fetch(
                                     nodeHashes: List[Hash],
                                     peerIds: NonEmptyList[PeerId]
                                 ): IO[Either[MptNodeFetcher.NodeFetchError, Unit]] =
                                   requestedNodeHashRef.update(_ ++ nodeHashes).as(Right(()))
                               }

          mockBranchExecution = new BranchExecution[IO] {
                                  override def execute(
                                      branch: BlocksBranch
                                  ): IO[Either[ObftBlockExecution.BlockExecutionError, BlocksBranch]] = ???
                                  override def executeRange(
                                      from: ObftHeader,
                                      to: ObftHeader
                                  ): IO[Either[BranchExecution.RangeExecutionError, Unit]] =
                                    requestedFullSyncValidationRef.update(_ :+ (from, to)).as(Right(()))
                                }
          mockFullSyncValidation = new FullSyncMode[IO](branchFetcherUnused, mockBranchExecution)

          fastSyncMode <- FastSyncMode[IO](branchFetcherUnused, mockMptNodeFetcher, mockFullSyncValidation)

          _                   <- fastSyncMode.validateBranch(from, to, NonEmptyList.of(PeerId("peer1")))
          requestedNodeHashes <- requestedNodeHashRef.get

          /** In reality the second call would be done with a different range (pivot block to the new tip),
            * but for the sake of simplicity we use the same range
            */
          _                                <- fastSyncMode.validateBranch(from, to, NonEmptyList.of(PeerId("peer1")))
          requestedFullSyncValidationCalls <- requestedFullSyncValidationRef.get
        } yield {
          assert(requestedNodeHashes == List(to.stateRoot))
          assert(requestedFullSyncValidationCalls == List((from, to)))
        }

        test.ioValue
      }
    }

    "fail with 'FastSyncFailure' if unable to download MPT nodes" in {
      forAll {
        for {
          chainLength           <- Gen.choose(2, 100)
          chain                 <- obftBlockChainGen(chainLength, fixtures.GenesisBlock.block, obftEmptyBodyBlockGen)
          fromIndex             <- Gen.choose(0, chainLength - 2)
          toIndex               <- Gen.choose(fromIndex + 1, chainLength - 1)
          branch                 = chain.slice(fromIndex, toIndex + 1)
          (fromHeader, toHeader) = (branch.head.header, branch.last.header)
        } yield (fromHeader, toHeader)
      } { case (from, to) =>
        val mockMptNodeFetcher = new MptNodeFetcher[IO] {
          override def fetch(
              nodeHashes: List[Hash],
              peerIds: NonEmptyList[PeerId]
          ): IO[Either[MptNodeFetcher.NodeFetchError, Unit]] =
            IO.pure(Left(MptNodeFetcher.NodeFetchError.PeersAskFailure(AllPeersTimedOut)))
        }

        val test = for {
          fastSyncValidation <- FastSyncMode[IO](branchFetcherUnused, mockMptNodeFetcher, fullSyncUnused)
          validationResult   <- fastSyncValidation.validateBranch(from, to, NonEmptyList.of(PeerId("peer1")))
        } yield {
          assert(validationResult.isLeft)
          validationResult match {
            case Right(_)                 => fail("Expected failure")
            case Left(FastSyncFailure(_)) => succeed
            case _                        => fail("Expected FastSyncFailure")
          }
        }

        test.ioValue
      }
    }

    "fail with 'EvmValidationByExecutionError' if unable to execute the remaining blocks" in {
      forAll {
        for {
          chainLength           <- Gen.choose(2, 100)
          chain                 <- obftBlockChainGen(chainLength, fixtures.GenesisBlock.block, obftEmptyBodyBlockGen)
          fromIndex             <- Gen.choose(0, chainLength - 2)
          toIndex               <- Gen.choose(fromIndex + 1, chainLength - 1)
          branch                 = chain.slice(fromIndex, toIndex + 1)
          (fromHeader, toHeader) = (branch.head.header, branch.last.header)
        } yield (fromHeader, toHeader)
      } { case (from, to) =>
        val test = for {
          requestedNodeHashRef <- Ref.of[IO, List[Hash]](List.empty)

          mockMptNodeFetcher = new MptNodeFetcher[IO] {
                                 override def fetch(
                                     nodeHashes: List[Hash],
                                     peerIds: NonEmptyList[PeerId]
                                 ): IO[Either[MptNodeFetcher.NodeFetchError, Unit]] =
                                   requestedNodeHashRef.update(_ ++ nodeHashes).as(Right(()))
                               }

          mockBranchExecution = new BranchExecution[IO] {
                                  override def execute(
                                      branch: BlocksBranch
                                  ): IO[Either[ObftBlockExecution.BlockExecutionError, BlocksBranch]] = ???

                                  override def executeRange(
                                      from: ObftHeader,
                                      to: ObftHeader
                                  ): IO[Either[BranchExecution.RangeExecutionError, Unit]] =
                                    IO.pure(Left(BranchExecution.RangeExecutionError.NoHeaders()))
                                }
          mockFullSyncValidation = new FullSyncMode[IO](branchFetcherUnused, mockBranchExecution)

          fastSyncMode <- FastSyncMode[IO](branchFetcherUnused, mockMptNodeFetcher, mockFullSyncValidation)

          _ <- fastSyncMode.validateBranch(from, to, NonEmptyList.of(PeerId("peer1")))

          /** In reality the second call would be done with a different range (pivot block to the new tip),
            * but for the sake of simplicity we use the same range
            */
          validationResult <- fastSyncMode.validateBranch(from, to, NonEmptyList.of(PeerId("peer1")))
        } yield assert(
          validationResult == Left(
            EvmValidationByExecutionError(show"${BranchExecution.RangeExecutionError.NoHeaders()}")
          )
        )

        test.ioValue
      }
    }
  }

  "FastSyncMode.fetchBranch" should {
    "download a branch with receipts during the first phase" in {
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

          fastSyncMode: FastSyncMode[IO] <- FastSyncMode[IO](
                                              branchWithReceiptsFetcher,
                                              mptNodeFetcherUnused,
                                              fullSyncUnused
                                            )

          result <- fastSyncMode.fetchBranch(from, to, NonEmptyList.one(PeerId("peer1")))

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

    "download a branch without receipts during the second phase (switch to full sync)" in {
      forAll {
        for {
          chainLength           <- Gen.choose(10, 100)
          chain                 <- obftBlockChainGen(chainLength, fixtures.GenesisBlock.block, obftSmallBlockGen)
          fromIndex             <- Gen.choose(0, chainLength - 10)
          toIndex               <- Gen.choose(fromIndex + 1, chainLength - 1)
          branch                 = chain.slice(fromIndex, toIndex + 1)
          middleIndex            = (fromIndex + toIndex) / 2
          middle                 = chain(middleIndex).header
          (fromHeader, toHeader) = (branch.head.header, branch.last.header)
        } yield (chain, branch, fromHeader, toHeader, middle)
      } { case (_, branch, from, to, middle) =>
        val test = for {
          downloadedReceiptsRef <- Ref.of[IO, List[BlockHash]](List.empty)
          mockStorageRef        <- Ref.of[IO, List[ObftBlock]](List.empty)

          mockBranchFetcher = new BranchFetcher[IO] {
                                override def fetchBranch(
                                    from: ObftHeader,
                                    to: ObftHeader,
                                    peerIds: NonEmptyList[PeerId]
                                ): IO[BranchFetcher.BranchFetcherResult] = {
                                  val storage = branch.filter { block =>
                                    block.header.number >= from.number && block.header.number <= to.number
                                  }

                                  storage
                                    .traverse { block =>
                                      mockStorageRef.get.flatMap { storage =>
                                        if (storage.contains(block)) ().pure[IO]
                                        else mockStorageRef.update(_ :+ block).void
                                      }
                                    }
                                    .as(
                                      BranchFetcher.BranchFetcherResult.Connected(from, to)
                                    )
                                }
                              }

          mockBranchProvider = new BranchProvider[IO] {
                                 override def getChildren(parent: BlockHash): IO[Seq[ObftHeader]]      = ???
                                 override def findSuffixesTips(parent: BlockHash): IO[Seq[ObftHeader]] = ???
                                 override def fetchChain(
                                     from: ObftHeader,
                                     to: ObftHeader
                                 ): IO[Either[BranchProvider.BranchRetrievalError, List[ObftHeader]]] =
                                   mockStorageRef.get.map { storage =>
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

          // full sync to fall back on
          fullSyncMode = new FullSyncMode[IO](mockBranchFetcher, branchExecutionUnused)

          fastSyncMode: FastSyncMode[IO] <- FastSyncMode[IO](
                                              branchWithReceiptsFetcher,
                                              mptNodeFetcherUnused,
                                              fullSyncMode
                                            )

          // first phase
          firstPhaseResult <- fastSyncMode.fetchBranch(from, middle, NonEmptyList.one(PeerId("peer1")))

          // collect results
          firstPhaseDownloadedReceipts <- downloadedReceiptsRef.get
          _                            <- downloadedReceiptsRef.set(List.empty) // reset

          // call validate to switch to full sync
          _ <- fastSyncMode.validateBranch(from, middle, NonEmptyList.one(PeerId("peer1")))

          // second phase
          secondPhaseResult <- fastSyncMode.fetchBranch(middle, to, NonEmptyList.one(PeerId("peer1")))

          downloadedBlocks              <- mockStorageRef.get
          secondPhaseDownloadedReceipts <- downloadedReceiptsRef.get
        } yield {
          assert(firstPhaseResult == BranchFetcher.BranchFetcherResult.Connected(from, middle))
          val firstPhaseExpectedDownloadedReceipts =
            branch
              .filter(_.header.number <= middle.number)
              .flatMap(block => if (block.header.containsTransactions) Some(block.hash) else None)

          assert(firstPhaseDownloadedReceipts == firstPhaseExpectedDownloadedReceipts)
          assert(secondPhaseResult == BranchFetcher.BranchFetcherResult.Connected(middle, to))
          assert(secondPhaseDownloadedReceipts == List.empty) // no receipts should be downloaded
          assert(downloadedBlocks == branch)                  // all blocks should be downloaded
        }

        test.ioValue
      }
    }
  }

  private def branchFetcherUnused: BranchFetcher[IO] = new BranchFetcher[IO] {
    override def fetchBranch(
        from: ObftHeader,
        to: ObftHeader,
        peerIds: NonEmptyList[PeerId]
    ): IO[BranchFetcher.BranchFetcherResult] = ???
  }

  private val branchExecutionUnused: BranchExecution[IO] = new BranchExecution[IO] {
    override def execute(
        branch: BlocksBranch
    ): IO[Either[ObftBlockExecution.BlockExecutionError, BlocksBranch]] = ???

    override def executeRange(
        from: ObftHeader,
        to: ObftHeader
    ): IO[Either[BranchExecution.RangeExecutionError, Unit]] = ???
  }

  private def mptNodeFetcherUnused: MptNodeFetcher[IO] = new MptNodeFetcher[IO] {
    override def fetch(
        nodeHashes: List[Hash],
        peerIds: NonEmptyList[PeerId]
    ): IO[Either[MptNodeFetcher.NodeFetchError, Unit]] = IO.pure(Right(()))
  }

  private val fullSyncUnused: FullSyncMode[IO] = new FullSyncMode[IO](branchFetcherUnused, branchExecutionUnused)
}
