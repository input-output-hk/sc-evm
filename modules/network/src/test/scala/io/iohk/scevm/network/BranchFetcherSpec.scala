package io.iohk.scevm.network

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.implicits.{showInterpolator, toTraverseOps}
import fs2.concurrent.Topic
import io.iohk.scevm.consensus.metrics.TimeToFinalizationTracker
import io.iohk.scevm.consensus.validators.HeaderValidator
import io.iohk.scevm.consensus.validators.HeaderValidator.HeaderInvalidSignature
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.{BlockchainStorage, BlocksReader, BlocksWriter}
import io.iohk.scevm.domain.{BlockHash, ObftBlock, ObftBody, ObftHeader}
import io.iohk.scevm.metrics.instruments.Counter
import io.iohk.scevm.testing.BlockGenerators.{obftBlockChainGen, obftBlockHeaderGen, obftEmptyBodyBlockGen}
import io.iohk.scevm.testing.{IOSupport, LongPatience, fixtures}
import org.scalacheck.Gen
import org.scalatest.Assertions
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.duration._

// scalastyle:off number.of.methods
class BranchFetcherSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with IOSupport
    with ScalaFutures
    with LongPatience {

  "BranchFetcherSpec.fetchBranch" when {
    "requested to download a branch" should {
      "download the branch and store the blocks in storage" in {
        forAll {
          for {
            chainLength <- Gen.choose(2, 100)
            chain       <- obftBlockChainGen(chainLength, fixtures.GenesisBlock.block, obftEmptyBodyBlockGen)
            maxRequestBlocks <-
              Gen.choose(1, chainLength) // Configuration parameter: number of blocks to request per message
            fromIndex             <- Gen.choose(0, chainLength - 2)
            toIndex               <- Gen.choose(fromIndex + 1, chainLength - 1)
            branch                 = chain.slice(fromIndex, toIndex + 1)
            (fromHeader, toHeader) = (branch.head.header, branch.last.header)
          } yield (chain, branch, fromHeader, toHeader, maxRequestBlocks)
        } { case (chain, branch, from, to, maxRequestBlocks) =>
          val test = for {
            peerEventsTopic  <- Topic[IO, PeerEvent]
            peerActionsTopic <- Topic[IO, PeerAction]

            branchFetcherConfig = BranchFetcherConfig(maxRequestBlocks, 1.second, 1)
            storedBlocks       <- Ref.of[IO, List[ObftBlock]](List())
            blockWriter = new BlocksWriter[IO] {
                            override def insertHeader(header: ObftHeader): IO[Unit] = IO.unit

                            override def insertBody(blockHash: BlockHash, body: ObftBody): IO[Unit] = IO.unit

                            override def insertBlock(block: ObftBlock)(implicit apF: Applicative[IO]): IO[Unit] =
                              storedBlocks.update(_ :+ block)
                          }
            blocksReader = new BlocksReader[IO] {
                             override def getBlockHeader(hash: BlockHash): IO[Option[ObftHeader]] =
                               getBlock(hash).map(_.map(_.header))

                             override def getBlockBody(hash: BlockHash): IO[Option[ObftBody]] =
                               getBlock(hash).map(_.map(_.body))

                             override def getBlock(hash: BlockHash): IO[Option[ObftBlock]] =
                               storedBlocks.get.map(_.find(_.header.hash == hash))
                           }

            blocksFetcher = BackwardBlocksFetcher[IO](
                              TopicBasedPeerChannel(
                                peerEventsTopic,
                                peerActionsTopic,
                                branchFetcherConfig.requestTimeout,
                                branchFetcherConfig.maxBlocksPerMessage
                              ),
                              blockWriter,
                              blocksReader,
                              alwaysValidHeaderValidator,
                              TimeToFinalizationTracker.noop[IO]
                            )

            branchFetcher =
              BackwardBranchFetcher(
                blocksFetcher,
                branchFetcherConfig,
                Counter.noop[IO]
              )

            fetchResult <- FullBlockResponseMock.runWithResponseMock(
                             branchFetcher.fetchBranch(from, to, NonEmptyList.of(PeerId("peerId"))),
                             peerEventsTopic,
                             peerActionsTopic,
                             chain
                           )
            testAssertions <- fetchResult match {
                                case BranchFetcher.BranchFetcherResult.Connected(actualFrom, actualTo) =>
                                  IO(
                                    assert(
                                      from == actualFrom,
                                      "BranchFetcherResult is Connected, but the start blocks are different"
                                    )
                                  ) >>
                                    IO(
                                      assert(
                                        to == actualTo,
                                        "The last block in the branch is not the one that was requested"
                                      )
                                    ) >>
                                    branch.traverse { block =>
                                      blocksReader.getBlock(block.hash).map { b =>
                                        assert(
                                          b.isDefined,
                                          show"Block $block was not found in storage"
                                        )
                                      }
                                    }
                                case BranchFetcher.BranchFetcherResult.Disconnected(
                                      expectedFrom,
                                      actualFrom,
                                      to
                                    ) =>
                                  IO(
                                    fail(
                                      show"Disconnected branch: expected from $expectedFrom, actual from $actualFrom, to $to"
                                    )
                                  )
                                case BranchFetcher.BranchFetcherResult.RecoverableError(message) =>
                                  IO(fail(show"BranchFetcher recoverable error: $message"))
                                case BranchFetcher.BranchFetcherResult.FatalError(message) =>
                                  IO(fail(show"BranchFetcher error: $message"))
                                case BranchFetcher.BranchFetcherResult.InvalidRange =>
                                  IO(fail("BranchFetcher invalid range"))
                              }
          } yield testAssertions
          test.ioValue
        }
      }

      "report 'BranchDisconnected' if the downloaded branch head is not equal to the requested one" in {
        forAll {
          for {
            chainLength <- Gen.choose(2, 100)
            chain       <- obftBlockChainGen(chainLength, fixtures.GenesisBlock.block, obftEmptyBodyBlockGen)
            maxRequestBlocks <-
              Gen.choose(1, chainLength) // Configuration parameter: number of blocks to request per message
            fromIndex             <- Gen.choose(0, chainLength - 2)
            toIndex               <- Gen.choose(fromIndex + 1, chainLength - 1)
            branch                 = chain.slice(fromIndex, toIndex + 1)
            (fromHeader, toHeader) = (branch.head.header, branch.last.header)
            fakeFromHeader        <- obftBlockHeaderGen.map(_.copy(number = fromHeader.number))
          } yield (chain, branch, toHeader, fakeFromHeader, maxRequestBlocks)
        } { case (chain, branch, to, fakeFromHeader, maxRequestBlocks) =>
          val test = for {
            peerEventsTopic  <- Topic[IO, PeerEvent]
            peerActionsTopic <- Topic[IO, PeerAction]

            branchFetcherConfig = BranchFetcherConfig(maxRequestBlocks, 1.second, 1)
            blockchainStorage   = BlockchainStorage.unsafeCreate[IO](EphemDataSource())

            blocksFetcher = BackwardBlocksFetcher[IO](
                              TopicBasedPeerChannel(
                                peerEventsTopic,
                                peerActionsTopic,
                                branchFetcherConfig.requestTimeout,
                                branchFetcherConfig.maxBlocksPerMessage
                              ),
                              blockchainStorage,
                              blockchainStorage,
                              alwaysValidHeaderValidator,
                              TimeToFinalizationTracker.noop[IO]
                            )

            branchFetcher =
              BackwardBranchFetcher(
                blocksFetcher,
                branchFetcherConfig,
                Counter.noop[IO]
              )

            from = fakeFromHeader // intentionally providing a block that `chain` does not contain

            fetchResult <- FullBlockResponseMock.runWithResponseMock(
                             branchFetcher.fetchBranch(from, to, NonEmptyList.of(PeerId("peerId"))),
                             peerEventsTopic,
                             peerActionsTopic,
                             chain
                           )
            testResult <- fetchResult match {
                            case BranchFetcher.BranchFetcherResult.Connected(actualFrom, actualTo) =>
                              IO(
                                fail(
                                  show"BranchFetcherResult is Connected, but the downloaded branch was incomplete. " +
                                    show"(actual from: ($actualFrom, actual to: $actualTo)"
                                )
                              )
                            case BranchFetcher.BranchFetcherResult.Disconnected(expectedFrom, actualFrom, _) =>
                              IO(
                                assert(
                                  expectedFrom != actualFrom,
                                  "BranchFetcherResult is Disconnected, but the start blocks are the same"
                                )
                              ) >>
                                branch.drop(1).traverse { block => // all but first
                                  blockchainStorage.getBlock(block.hash).map { b =>
                                    assert(
                                      b.isDefined,
                                      show"Block $block was not found in storage. Disconnected branch should be stored."
                                    )
                                  }
                                }
                            case BranchFetcher.BranchFetcherResult.RecoverableError(message) =>
                              IO(fail(s"BranchFetcher recoverable error: $message"))
                            case BranchFetcher.BranchFetcherResult.FatalError(message) =>
                              IO(fail(s"BranchFetcher error: $message"))
                            case BranchFetcher.BranchFetcherResult.InvalidRange =>
                              IO(fail("BranchFetcher invalid range"))
                          }
          } yield testResult
          test.ioValue
        }
      }

      "report 'InvalidRange' if `from` > `to`" in {
        forAll {
          for {
            chainLength <- Gen.choose(2, 100)
            chain       <- obftBlockChainGen(chainLength, fixtures.GenesisBlock.block, obftEmptyBodyBlockGen)
            maxRequestBlocks <-
              Gen.choose(1, chainLength) // Configuration parameter: number of blocks to request per message
            fromIndex             <- Gen.choose(0, chainLength - 2)
            toIndex               <- Gen.choose(fromIndex + 1, chainLength - 1)
            branch                 = chain.slice(fromIndex, toIndex + 1)
            (fromHeader, toHeader) = (branch.head.header, branch.last.header)
          } yield (fromHeader, toHeader, maxRequestBlocks)
        } { case (from, to, maxRequestBlocks) =>
          val test = for {
            peerEventsTopic  <- Topic[IO, PeerEvent]
            peerActionsTopic <- Topic[IO, PeerAction]

            branchFetcherConfig = BranchFetcherConfig(maxRequestBlocks, 1.second, 1)
            blockchainStorage   = BlockchainStorage.unsafeCreate[IO](EphemDataSource())
            blocksFetcher = BackwardBlocksFetcher[IO](
                              TopicBasedPeerChannel(
                                peerEventsTopic,
                                peerActionsTopic,
                                branchFetcherConfig.requestTimeout,
                                branchFetcherConfig.maxBlocksPerMessage
                              ),
                              blockchainStorage,
                              blockchainStorage,
                              alwaysValidHeaderValidator,
                              TimeToFinalizationTracker.noop[IO]
                            )
            branchFetcher =
              BackwardBranchFetcher(
                blocksFetcher,
                branchFetcherConfig,
                Counter.noop[IO]
              )

            fetchResult <-
              branchFetcher.fetchBranch(to, from, NonEmptyList.of(PeerId("peerId")))

            testResult <- fetchResult match {
                            case BranchFetcher.BranchFetcherResult.InvalidRange =>
                              IO(Assertions.succeed)
                            case _: BranchFetcher.BranchFetcherResult.Connected =>
                              IO(
                                fail(
                                  s"BranchFetcherResult is Connected, but `from` > `to` "
                                )
                              )
                            case _: BranchFetcher.BranchFetcherResult.Disconnected =>
                              IO(
                                fail(
                                  s"BranchFetcherResult is Disconnected, but `from` > `to`"
                                )
                              )
                            case BranchFetcher.BranchFetcherResult.RecoverableError(message) =>
                              IO(fail(s"BranchFetcher recoverable error: $message"))
                            case BranchFetcher.BranchFetcherResult.FatalError(message) =>
                              IO(fail(s"BranchFetcher error: $message"))
                          }
          } yield testResult
          test.ioValue
        }
      }

      "report 'RecoverableError' if unable to download a branch due to unavailable peers" in {
        forAll {
          for {
            chainLength <- Gen.choose(2, 100)
            chain       <- obftBlockChainGen(chainLength, fixtures.GenesisBlock.block, obftEmptyBodyBlockGen)
            maxRequestBlocks <-
              Gen.choose(1, chainLength) // Configuration parameter: number of blocks to request per message
            fromIndex             <- Gen.choose(0, chainLength - 2)
            toIndex               <- Gen.choose(fromIndex + 1, chainLength - 1)
            branch                 = chain.slice(fromIndex, toIndex + 1)
            (fromHeader, toHeader) = (branch.head.header, branch.last.header)
          } yield (fromHeader, toHeader, maxRequestBlocks)
        } { case (from, to, maxRequestBlocks) =>
          val test = for {
            peerEventsTopic  <- Topic[IO, PeerEvent] // remains empty
            peerActionsTopic <- Topic[IO, PeerAction] // remains empty

            branchFetcherConfig =
              BranchFetcherConfig(maxRequestBlocks, 0.second, 1) // immediate timeout as there is no producer
            blockchainStorage = BlockchainStorage.unsafeCreate[IO](EphemDataSource())
            blocksFetcher = BackwardBlocksFetcher[IO](
                              TopicBasedPeerChannel(
                                peerEventsTopic,
                                peerActionsTopic,
                                branchFetcherConfig.requestTimeout,
                                branchFetcherConfig.maxBlocksPerMessage
                              ),
                              blockchainStorage,
                              blockchainStorage,
                              alwaysValidHeaderValidator,
                              TimeToFinalizationTracker.noop[IO]
                            )
            branchFetcher =
              BackwardBranchFetcher(
                blocksFetcher,
                branchFetcherConfig,
                Counter.noop[IO]
              )

            fetchResult <-
              branchFetcher.fetchBranch(from, to, NonEmptyList.of(PeerId("peerId")))

            testResult <- fetchResult match {
                            case BranchFetcher.BranchFetcherResult.FatalError(message) =>
                              IO(fail(s"BranchFetcher fatal error: $message"))
                            case BranchFetcher.BranchFetcherResult.InvalidRange =>
                              IO(fail("InvalidRange, but should return an error"))
                            case _: BranchFetcher.BranchFetcherResult.Connected =>
                              IO(
                                fail(
                                  s"BranchFetcherResult is Connected, but should return an error"
                                )
                              )
                            case _: BranchFetcher.BranchFetcherResult.Disconnected =>
                              IO(
                                fail(
                                  s"BranchFetcherResult is Disconnected, but should return an error`"
                                )
                              )
                            case BranchFetcher.BranchFetcherResult.RecoverableError(_) =>
                              IO(Assertions.succeed)
                          }
          } yield testResult
          test.ioValue
        }
      }

      "not request any blocks if the branch is already downloaded" in {
        forAll {
          for {
            chainLength <- Gen.choose(2, 100)
            chain       <- obftBlockChainGen(chainLength, fixtures.GenesisBlock.block, obftEmptyBodyBlockGen)
            maxRequestBlocks <-
              Gen.choose(1, chainLength)
            fromIndex             <- Gen.choose(0, chainLength - 2)
            toIndex               <- Gen.choose(fromIndex + 1, chainLength - 1)
            branch                 = chain.slice(fromIndex, toIndex + 1)
            (fromHeader, toHeader) = (branch.head.header, branch.last.header)
          } yield (chain, branch, fromHeader, toHeader, maxRequestBlocks)
        } { case (chain, branch, from, to, maxRequestBlocks) =>
          val test = for {
            peerEventsTopic  <- Topic[IO, PeerEvent]
            peerActionsTopic <- Topic[IO, PeerAction]

            branchFetcherConfig = BranchFetcherConfig(maxRequestBlocks, 1.second, 1)
            blocksWritten      <- Ref.of[IO, List[ObftBlock]](List())
            blockWriter = new BlocksWriter[IO] {
                            override def insertHeader(header: ObftHeader): IO[Unit] = IO.unit

                            override def insertBody(blockHash: BlockHash, body: ObftBody): IO[Unit] = IO.unit

                            override def insertBlock(block: ObftBlock)(implicit apF: Applicative[IO]): IO[Unit] =
                              blocksWritten.update(_ :+ block) // shouldn't be called
                          }
            blocksReader = new BlocksReader[IO] {
                             override def getBlockHeader(hash: BlockHash): IO[Option[ObftHeader]] =
                               getBlock(hash).map(_.map(_.header))

                             override def getBlockBody(hash: BlockHash): IO[Option[ObftBody]] =
                               getBlock(hash).map(_.map(_.body))

                             override def getBlock(hash: BlockHash): IO[Option[ObftBlock]] =
                               IO(branch.find(_.header.hash == hash)) // the entire branch is in the storage
                           }

            blocksFetcher = BackwardBlocksFetcher[IO](
                              TopicBasedPeerChannel(
                                peerEventsTopic,
                                peerActionsTopic,
                                branchFetcherConfig.requestTimeout,
                                branchFetcherConfig.maxBlocksPerMessage
                              ),
                              blockWriter,
                              blocksReader,
                              alwaysValidHeaderValidator,
                              TimeToFinalizationTracker.noop[IO]
                            )
            branchFetcher =
              BackwardBranchFetcher(
                blocksFetcher,
                branchFetcherConfig,
                Counter.noop[IO]
              )

            fetchResult <- FullBlockResponseMock.runWithResponseMock(
                             branchFetcher.fetchBranch(from, to, NonEmptyList.of(PeerId("peerId"))),
                             peerEventsTopic,
                             peerActionsTopic,
                             chain
                           )
            testAssertions <- fetchResult match {
                                case BranchFetcher.BranchFetcherResult.Connected(actualFrom, actualTo) =>
                                  IO(
                                    assert(
                                      from == actualFrom,
                                      "BranchFetcherResult is Connected, but the start blocks are different"
                                    )
                                  ) >>
                                    IO(
                                      assert(
                                        to == actualTo,
                                        "The last block in the branch is not the one that was requested"
                                      )
                                    ) >>
                                    blocksWritten.get.map { blocks =>
                                      assert(
                                        blocks.isEmpty,
                                        "Some blocks were written to the storage while the branch was already there"
                                      )
                                    }
                                case BranchFetcher.BranchFetcherResult.Disconnected(
                                      expectedFrom,
                                      actualFrom,
                                      to
                                    ) =>
                                  IO(
                                    fail(
                                      show"Disconnected branch: expected from $expectedFrom, actual from $actualFrom, to $to"
                                    )
                                  )
                                case BranchFetcher.BranchFetcherResult.RecoverableError(message) =>
                                  IO(fail(s"BranchFetcher recoverable error: $message"))
                                case BranchFetcher.BranchFetcherResult.FatalError(message) =>
                                  IO(fail(s"BranchFetcher error: $message"))
                                case BranchFetcher.BranchFetcherResult.InvalidRange =>
                                  IO(fail("BranchFetcher invalid range"))
                              }
          } yield testAssertions
          test.ioValue
        }
      }

      //  `from`                                            `to`
      //    O <- O <- O <- O <- O <- B6 <- B7 <- B8 <- B9 <- B10
      //   |_____________________|  |___________________________|
      //          to download                downloaded
      "not request newer blocks if they are already in the storage" in {
        forAll {
          for {
            chainLength           <- Gen.choose(2, 100)
            chain                 <- obftBlockChainGen(chainLength, fixtures.GenesisBlock.block, obftEmptyBodyBlockGen)
            maxRequestBlocks       = chainLength
            fromIndex             <- Gen.choose(0, chainLength - 2)
            toIndex               <- Gen.choose(fromIndex + 1, chainLength - 1)
            branch                 = chain.slice(fromIndex, toIndex + 1)
            (fromHeader, toHeader) = (branch.head.header, branch.last.header)
          } yield (chain, branch, fromHeader, toHeader, maxRequestBlocks)
        } { case (chain, branch, from, to, maxRequestBlocks) =>
          val test = for {
            peerEventsTopic  <- Topic[IO, PeerEvent]
            peerActionsTopic <- Topic[IO, PeerAction]

            branchFetcherConfig = BranchFetcherConfig(maxRequestBlocks, 1.second, 1)

            /** `branch` is a sub-chain of `chain` defined by [`from`, `to`] */
            (blocksToDownload, alreadyDownloadedBlocks) = branch.splitAt(branch.length / 2)

            blocksWritten <- Ref.of[IO, List[ObftBlock]](List())
            blocksWriter = new BlocksWriter[IO] {
                             override def insertHeader(header: ObftHeader): IO[Unit] = IO.unit

                             override def insertBody(blockHash: BlockHash, body: ObftBody): IO[Unit] = IO.unit

                             override def insertBlock(block: ObftBlock)(implicit apF: Applicative[IO]): IO[Unit] =
                               blocksWritten.update(_ :+ block)
                           }
            blocksReader = new BlocksReader[IO] {
                             override def getBlockHeader(hash: BlockHash): IO[Option[ObftHeader]] =
                               getBlock(hash).map(_.map(_.header))

                             override def getBlockBody(hash: BlockHash): IO[Option[ObftBody]] =
                               getBlock(hash).map(_.map(_.body))

                             override def getBlock(hash: BlockHash): IO[Option[ObftBlock]] =
                               IO(
                                 alreadyDownloadedBlocks.find(_.header.hash == hash)
                               ) // older half of the branch is downloaded
                           }

            blocksFetcher = BackwardBlocksFetcher[IO](
                              TopicBasedPeerChannel(
                                peerEventsTopic,
                                peerActionsTopic,
                                branchFetcherConfig.requestTimeout,
                                branchFetcherConfig.maxBlocksPerMessage
                              ),
                              blocksWriter,
                              blocksReader,
                              alwaysValidHeaderValidator,
                              TimeToFinalizationTracker.noop[IO]
                            )
            branchFetcher =
              BackwardBranchFetcher(
                blocksFetcher,
                branchFetcherConfig,
                Counter.noop[IO]
              )

            fetchResult <- FullBlockResponseMock.runWithResponseMock(
                             branchFetcher.fetchBranch(from, to, NonEmptyList.of(PeerId("peerId"))),
                             peerEventsTopic,
                             peerActionsTopic,
                             chain
                           )
            testAssertions <- fetchResult match {
                                case BranchFetcher.BranchFetcherResult.Connected(actualFrom, actualTo) =>
                                  IO(
                                    assert(
                                      from == actualFrom,
                                      "BranchFetcherResult is Connected, but the start blocks are different"
                                    )
                                  ) >>
                                    IO(
                                      assert(
                                        to == actualTo,
                                        "The last block in the branch is not the one that was requested"
                                      )
                                    ) >>
                                    blocksWritten.get.map { blocks =>
                                      assert(
                                        blocks.reverse == blocksToDownload,
                                        if (blocks.length > blocksToDownload.length)
                                          s"Some blocks were downloaded twice"
                                        else
                                          s"Some blocks were not downloaded"
                                      )
                                    }
                                case BranchFetcher.BranchFetcherResult.Disconnected(
                                      expectedFrom,
                                      actualFrom,
                                      to
                                    ) =>
                                  IO(
                                    fail(
                                      show"Disconnected branch: expected from $expectedFrom, actual from $actualFrom, to $to"
                                    )
                                  )
                                case BranchFetcher.BranchFetcherResult.RecoverableError(message) =>
                                  IO(fail(s"BranchFetcher recoverable error: $message"))
                                case BranchFetcher.BranchFetcherResult.FatalError(message) =>
                                  IO(fail(s"BranchFetcher error: $message"))
                                case BranchFetcher.BranchFetcherResult.InvalidRange =>
                                  IO(fail("BranchFetcher invalid range"))
                              }
          } yield testAssertions
          test.ioValue
        }
      }

      // `from`                                                  `to`
      //   B1 <- B2 <- B3 <- B4 <- B5 <- O <- O <- O <- O <- O <- O
      //  |__________________________| |___________________________|
      //      already downloaded               new blocks
      //  |________________________________________________________|
      //                       to be downloaded
      "request all blocks, even older blocks that are already in the storage" in {
        forAll {
          for {
            chainLength           <- Gen.choose(2, 100)
            chain                 <- obftBlockChainGen(chainLength, fixtures.GenesisBlock.block, obftEmptyBodyBlockGen)
            maxRequestBlocks       = chainLength
            fromIndex             <- Gen.choose(0, chainLength - 2)
            toIndex               <- Gen.choose(fromIndex + 1, chainLength - 1)
            branch                 = chain.slice(fromIndex, toIndex + 1)
            (fromHeader, toHeader) = (branch.head.header, branch.last.header)
          } yield (chain, branch, fromHeader, toHeader, maxRequestBlocks)
        } { case (chain, branch, from, to, maxRequestBlocks) =>
          val test = for {
            peerEventsTopic  <- Topic[IO, PeerEvent]
            peerActionsTopic <- Topic[IO, PeerAction]

            branchFetcherConfig = BranchFetcherConfig(maxRequestBlocks, 1.second, 1)

            /** `branch` is a sub-chain of `chain` defined by [`from`, `to`] */
            (alreadyDownloadedBlocks, _) = branch.splitAt(branch.length / 2)

            blocksWritten <- Ref.of[IO, List[ObftBlock]](List())
            blocksWriter = new BlocksWriter[IO] {
                             override def insertHeader(header: ObftHeader): IO[Unit] = IO.unit

                             override def insertBody(blockHash: BlockHash, body: ObftBody): IO[Unit] = IO.unit

                             override def insertBlock(block: ObftBlock)(implicit apF: Applicative[IO]): IO[Unit] =
                               blocksWritten.update(_ :+ block)
                           }
            blocksReader = new BlocksReader[IO] {
                             override def getBlockHeader(hash: BlockHash): IO[Option[ObftHeader]] =
                               getBlock(hash).map(_.map(_.header))

                             override def getBlockBody(hash: BlockHash): IO[Option[ObftBody]] =
                               getBlock(hash).map(_.map(_.body))

                             override def getBlock(hash: BlockHash): IO[Option[ObftBlock]] =
                               IO(
                                 alreadyDownloadedBlocks.find(_.header.hash == hash)
                               ) // earlier half of the branch is downloaded
                           }

            blocksFetcher = BackwardBlocksFetcher[IO](
                              TopicBasedPeerChannel(
                                peerEventsTopic,
                                peerActionsTopic,
                                branchFetcherConfig.requestTimeout,
                                branchFetcherConfig.maxBlocksPerMessage
                              ),
                              blocksWriter,
                              blocksReader,
                              alwaysValidHeaderValidator,
                              TimeToFinalizationTracker.noop[IO]
                            )
            branchFetcher =
              BackwardBranchFetcher(
                blocksFetcher,
                branchFetcherConfig,
                Counter.noop[IO]
              )

            fetchResult <- FullBlockResponseMock.runWithResponseMock(
                             branchFetcher.fetchBranch(from, to, NonEmptyList.of(PeerId("peerId"))),
                             peerEventsTopic,
                             peerActionsTopic,
                             chain
                           )
            testAssertions <- fetchResult match {
                                case BranchFetcher.BranchFetcherResult.Connected(actualFrom, actualTo) =>
                                  IO(
                                    assert(
                                      from == actualFrom,
                                      "BranchFetcherResult is Connected, but the start blocks are different"
                                    )
                                  ) >>
                                    IO(
                                      assert(
                                        to == actualTo,
                                        "The last block in the branch is not the one that was requested"
                                      )
                                    ) >>
                                    blocksWritten.get.map { blocks =>
                                      assert(
                                        blocks.reverse == branch,
                                        s"The entire chain of ${branch.length} should've been downloaded, but only ${blocks.length} were"
                                      )
                                    }
                                case BranchFetcher.BranchFetcherResult.Disconnected(
                                      expectedFrom,
                                      actualFrom,
                                      to
                                    ) =>
                                  IO(
                                    fail(
                                      show"Disconnected branch: expected from $expectedFrom, actual from $actualFrom, to $to"
                                    )
                                  )
                                case BranchFetcher.BranchFetcherResult.RecoverableError(message) =>
                                  IO(fail(s"BranchFetcher recoverable error: $message"))
                                case BranchFetcher.BranchFetcherResult.FatalError(message) =>
                                  IO(fail(s"BranchFetcher error: $message"))
                                case BranchFetcher.BranchFetcherResult.InvalidRange =>
                                  IO(fail("BranchFetcher invalid range"))
                              }
          } yield testAssertions
          test.ioValue
        }
      }

      "report 'RecoverableError' if some of the downloaded blocks are invalid" in {
        forAll {
          for {
            chainLength <- Gen.choose(2, 100)
            chain       <- obftBlockChainGen(chainLength, fixtures.GenesisBlock.block, obftEmptyBodyBlockGen)
            maxRequestBlocks <-
              Gen.choose(1, chainLength) // Configuration parameter: number of blocks to request per message
            fromIndex             <- Gen.choose(0, chainLength - 2)
            toIndex               <- Gen.choose(fromIndex + 1, chainLength - 1)
            branch                 = chain.slice(fromIndex, toIndex + 1)
            (fromHeader, toHeader) = (branch.head.header, branch.last.header)
          } yield (chain, branch, fromHeader, toHeader, maxRequestBlocks)
        } { case (chain, _, from, to, maxRequestBlocks) =>
          val test = for {
            peerEventsTopic  <- Topic[IO, PeerEvent]
            peerActionsTopic <- Topic[IO, PeerAction]

            branchFetcherConfig = BranchFetcherConfig(maxRequestBlocks, 1.second, 1)
            storedBlocks       <- Ref.of[IO, List[ObftBlock]](List())
            blockWriter = new BlocksWriter[IO] {
                            override def insertHeader(header: ObftHeader): IO[Unit] = IO.unit

                            override def insertBody(blockHash: BlockHash, body: ObftBody): IO[Unit] = IO.unit

                            override def insertBlock(block: ObftBlock)(implicit apF: Applicative[IO]): IO[Unit] =
                              storedBlocks.update(_ :+ block)
                          }
            blocksReader = new BlocksReader[IO] {
                             override def getBlockHeader(hash: BlockHash): IO[Option[ObftHeader]] =
                               getBlock(hash).map(_.map(_.header))

                             override def getBlockBody(hash: BlockHash): IO[Option[ObftBody]] =
                               getBlock(hash).map(_.map(_.body))

                             override def getBlock(hash: BlockHash): IO[Option[ObftBlock]] =
                               storedBlocks.get.map(_.find(_.header.hash == hash))
                           }

            blocksFetcher = BackwardBlocksFetcher[IO](
                              TopicBasedPeerChannel(
                                peerEventsTopic,
                                peerActionsTopic,
                                branchFetcherConfig.requestTimeout,
                                branchFetcherConfig.maxBlocksPerMessage
                              ),
                              blockWriter,
                              blocksReader,
                              (header: ObftHeader) =>
                                IO.pure(Left(HeaderInvalidSignature(header))), // invalid signature
                              TimeToFinalizationTracker.noop[IO]
                            )

            branchFetcher =
              BackwardBranchFetcher(
                blocksFetcher,
                branchFetcherConfig,
                Counter.noop[IO]
              )

            fetchResult <- FullBlockResponseMock.runWithResponseMock(
                             branchFetcher.fetchBranch(from, to, NonEmptyList.of(PeerId("peerId"))),
                             peerEventsTopic,
                             peerActionsTopic,
                             chain
                           )
            testResult <- fetchResult match {
                            case BranchFetcher.BranchFetcherResult.FatalError(message) =>
                              IO(fail(s"BranchFetcher fatal error: $message"))
                            case BranchFetcher.BranchFetcherResult.InvalidRange =>
                              IO(fail("InvalidRange, but should return an error"))
                            case _: BranchFetcher.BranchFetcherResult.Connected =>
                              IO(
                                fail(
                                  s"BranchFetcherResult is Connected, but should return an error"
                                )
                              )
                            case _: BranchFetcher.BranchFetcherResult.Disconnected =>
                              IO(
                                fail(
                                  s"BranchFetcherResult is Disconnected, but should return an error`"
                                )
                              )
                            case BranchFetcher.BranchFetcherResult.RecoverableError(_) =>
                              IO(Assertions.succeed)
                          }
          } yield testResult
          test.ioValue
        }
      }
    }
  }

  def alwaysValidHeaderValidator: HeaderValidator[IO] = (header: ObftHeader) => IO.pure(Right(header))
}
