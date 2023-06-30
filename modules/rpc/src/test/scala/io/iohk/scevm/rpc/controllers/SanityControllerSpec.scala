package io.iohk.scevm.rpc.controllers

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.concurrent.SignallingRef
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.db.storage.{BlocksReader, StableNumberMappingStorage}
import io.iohk.scevm.domain.{BlockHash, BlockNumber, ObftBlock}
import io.iohk.scevm.rpc.ServiceResponse
import io.iohk.scevm.rpc.controllers.SanityController.{
  ChainConsistencyError,
  CheckChainConsistencyRequest,
  CheckChainConsistencyResponse,
  InconsistentBlockHash,
  InconsistentBlockNumber,
  InvalidParentHash,
  MissingBlockBody,
  MissingBlockHeader,
  MissingStableNumberMappingEntry
}
import io.iohk.scevm.rpc.controllers.SanityControllerSpec.{EntriesGen, RangeGen}
import io.iohk.scevm.rpc.domain.JsonRpcError
import io.iohk.scevm.testing.BlockGenerators.{blockHashGen, obftEmptyBodyBlockGen}
import io.iohk.scevm.testing.{BlockGenerators, fixtures}
import org.scalacheck.Gen
import org.scalacheck.Shrink.shrinkAny
import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

object SanityControllerSpec {
  def EntriesGen(stableBlockNumber: Int): Gen[Set[BlockNumber]] =
    Gen.listOfN(stableBlockNumber, Gen.choose(0, stableBlockNumber)).map(_.toSet).map(_.map(BlockNumber(_)))
  def RangeGen(stableBlockNumber: Int): Gen[(Int, Int)] = for {
    from <- Gen.choose(0, stableBlockNumber)
    to   <- Gen.choose(0, stableBlockNumber)
  } yield if (from <= to) (from, to) else (to, from)
}
class SanityControllerSpec
    extends AnyFlatSpec
    with Matchers
    with BlocksFixture
    with MockFactory
    with ScalaCheckPropertyChecks {

  val genesisBlockNumber = 0
  val genesisBlock       = fixtures.GenesisBlock.block
  val stableBlockNumber  = 25

  // without range -> [0; stable]
  "checkChainConsistency" should "returns the range [0; stable] when from and to are not specified" in {
    forAll(Gen.choose(genesisBlockNumber, 100)) { stableBlockNumber =>
      checkRange(stableBlockNumber, None, None, genesisBlockNumber, stableBlockNumber)
    }
  }

  // with from < 0 -> [0; stable]
  it should "update the from value to genesis if it's negative" in {
    forAll(Gen.choose(-100, genesisBlockNumber)) { negativeBlockNumber =>
      checkRange(stableBlockNumber, Some(negativeBlockNumber), None, genesisBlockNumber, stableBlockNumber)
    }
  }

  // with from >= 0 -> [from; stable]
  it should "keep the from value if it's in the range [0; stable]" in {
    forAll(Gen.choose(genesisBlockNumber, stableBlockNumber)) { inRangeBlockNumber =>
      checkRange(stableBlockNumber, Some(inRangeBlockNumber), None, inRangeBlockNumber, stableBlockNumber)
    }
  }

  // with to <= stable -> [0; to]
  it should "keep the to value to stable if it's under stable" in {
    forAll(Gen.choose(genesisBlockNumber, stableBlockNumber)) { blockNumber =>
      checkRange(stableBlockNumber, None, Some(blockNumber), genesisBlockNumber, blockNumber)
    }
  }

  // with to > stable -> [0; stable]
  it should "update the to value to stable if it's above stable" in {
    forAll(Gen.choose(stableBlockNumber + 1, stableBlockNumber + 1000)) { biggerThanStableBlockNumber =>
      checkRange(stableBlockNumber, None, Some(biggerThanStableBlockNumber), genesisBlockNumber, stableBlockNumber)
    }
  }

  // with from > to && to in [0; stable] -> error
  it should "return an error when from is strictly greater than to" in {
    forAll(Gen.choose(genesisBlockNumber, stableBlockNumber - 1)) { case to =>
      val from = to + 1
      checkJsonRPCError(
        stableBlockNumber,
        Some(from),
        Some(to),
        JsonRpcError.InvalidParams(s"from=$from should be lower or equals than to=$to")
      )
    }
  }

  it should "respond with the number of missing entries and their list" in {
    forAll(EntriesGen(stableBlockNumber), RangeGen(stableBlockNumber)) { case (availableEntries, (from, to)) =>
      val expectedMissingEntries =
        Range.inclusive(from, to).filter(i => !availableEntries.contains(BlockNumber(i))).toList

      val chain = BlockGenerators
        .obftBlockChainGen(stableBlockNumber, genesisBlock, obftEmptyBodyBlockGen)
        .sample
        .get
        .filter(block => availableEntries.contains(block.number))

      val expectedErrors = expectedMissingEntries.map(i => MissingStableNumberMappingEntry.apply(BlockNumber(i)))

      checkMissingEntries(
        customStableNumberMappingStorage(chain),
        customBlocksReader(chain),
        stableBlockNumber,
        from,
        to,
        expectedErrors
      )
    }
  }

  it should "report missing block headers" in {
    forAll(EntriesGen(stableBlockNumber), RangeGen(stableBlockNumber)) { case (availableEntries, (from, to)) =>
      val expectedMissingEntries =
        Range.inclusive(from, to).filter(i => !availableEntries.contains(BlockNumber(i))).toList

      val completeChain = BlockGenerators
        .obftBlockChainGen(stableBlockNumber, genesisBlock, obftEmptyBodyBlockGen)
        .sample
        .get
      val incompleteChain = completeChain.filter(block => availableEntries.contains(block.number))

      val expectedErrors = expectedMissingEntries.map(i => MissingBlockHeader.apply(completeChain(i).hash))

      checkMissingEntries(
        customStableNumberMappingStorage(completeChain),
        customBlocksReader(incompleteChain, completeChain),
        stableBlockNumber,
        from,
        to,
        expectedErrors
      )
    }
  }

  it should "report missing block bodies" in {
    forAll(EntriesGen(stableBlockNumber), RangeGen(stableBlockNumber)) { case (availableEntries, (from, to)) =>
      val expectedMissingEntries =
        Range.inclusive(from, to).filter(i => !availableEntries.contains(BlockNumber(i))).toList

      val completeChain = BlockGenerators
        .obftBlockChainGen(stableBlockNumber, genesisBlock, obftEmptyBodyBlockGen)
        .sample
        .get
      val incompleteChain = completeChain.filter(block => availableEntries.contains(block.number))

      val expectedErrors = expectedMissingEntries.map(i => MissingBlockBody.apply(completeChain(i).hash))

      checkMissingEntries(
        customStableNumberMappingStorage(completeChain),
        customBlocksReader(completeChain, incompleteChain),
        stableBlockNumber,
        from,
        to,
        expectedErrors
      )
    }
  }

  it should "report inconsistent block number and hash" in {
    forAll(Gen.choose(1, stableBlockNumber)) { corruptedEntryIndex =>
      val validChain = BlockGenerators
        .obftBlockChainGen(stableBlockNumber, genesisBlock, obftEmptyBodyBlockGen)
        .sample
        .get
      val corruptedBlockNumberChain = validChain.map {
        case b if b.number == BlockNumber(corruptedEntryIndex) =>
          b.copy(header = b.header.copy(number = b.header.number.next))
        case b => b
      }

      val expectedErrors = List(
        InconsistentBlockNumber(BlockNumber(corruptedEntryIndex), corruptedBlockNumberChain(corruptedEntryIndex)),
        // hash is computed from other fields, including number. Changing number implies changing hash
        InconsistentBlockHash(validChain(corruptedEntryIndex).hash, corruptedBlockNumberChain(corruptedEntryIndex))
      )

      checkMissingEntries(
        customStableNumberMappingStorage(validChain),
        corruptedBlocksReader(validChain.map(_.hash), corruptedBlockNumberChain),
        stableBlockNumber,
        genesisBlockNumber,
        stableBlockNumber,
        expectedErrors
      )
    }
  }

  it should "report inconsistent invalid parent hash" in {
    forAll(Gen.choose(1, stableBlockNumber)) { corruptedEntryIndex =>
      val firstPart = BlockGenerators
        .obftBlockChainGen(corruptedEntryIndex - 1, genesisBlock, obftEmptyBodyBlockGen)
        .sample
        .get
      val splitBlock = firstPart.last
      val secondPart = BlockGenerators
        .obftBlockChainGen(
          stableBlockNumber - corruptedEntryIndex + 1,
          splitBlock.copy(header = splitBlock.header.copy(parentHash = blockHashGen.sample.get)),
          obftEmptyBodyBlockGen
        )
        .sample
        .get
        .tail
      val corruptedChain = firstPart ++ secondPart

      val expectedErrors = List(
        InvalidParentHash(corruptedChain(corruptedEntryIndex), corruptedChain(corruptedEntryIndex - 1))
      )

      checkMissingEntries(
        customStableNumberMappingStorage(corruptedChain),
        customBlocksReader(corruptedChain),
        stableBlockNumber,
        genesisBlockNumber,
        stableBlockNumber,
        expectedErrors
      )
    }
  }

  private def checkRange(
      stableBlockNumber: Int,
      fromOpt: Option[Int],
      toOpt: Option[Int],
      expectedFrom: Int,
      expectedTo: Int
  ): Unit = {
    val fromBlockNumberOpt      = fromOpt.map(BlockNumber.apply(_))
    val toBlockNumberOpt        = toOpt.map(BlockNumber.apply(_))
    val expectedFromBlockNumber = BlockNumber(expectedFrom)
    val expectedToBlockNumber   = BlockNumber(expectedTo)

    val chain = BlockGenerators.obftBlockChainGen(stableBlockNumber, genesisBlock, obftEmptyBodyBlockGen).sample.get

    val test = for {
      response <- computeResponse(
                    customStableNumberMappingStorage(chain),
                    customBlocksReader(chain),
                    stableBlockNumber,
                    fromBlockNumberOpt,
                    toBlockNumberOpt
                  )
    } yield response shouldBe Right(
      CheckChainConsistencyResponse(expectedFromBlockNumber, expectedToBlockNumber, List.empty)
    )

    test.unsafeRunSync()
  }

  private def checkJsonRPCError(
      stableBlockNumber: Int,
      fromOpt: Option[Int],
      toOpt: Option[Int],
      expectedJsonRpcError: JsonRpcError
  ): Unit = {
    val fromBlockNumberOpt = fromOpt.map(BlockNumber.apply(_))
    val toBlockNumberOpt   = toOpt.map(BlockNumber.apply(_))

    val chain = BlockGenerators.obftBlockChainGen(stableBlockNumber, genesisBlock, obftEmptyBodyBlockGen).sample.get

    val test = for {
      response <- computeResponse(
                    customStableNumberMappingStorage(chain),
                    customBlocksReader(chain),
                    stableBlockNumber,
                    fromBlockNumberOpt,
                    toBlockNumberOpt
                  )
    } yield response shouldBe Left(expectedJsonRpcError)

    test.unsafeRunSync()
  }

  def checkMissingEntries(
      stableNumberMappingStorage: StableNumberMappingStorage[IO],
      blockchainStorage: BlocksReader[IO],
      stableBlockNumber: Int,
      from: Int,
      to: Int,
      expectedErrors: List[ChainConsistencyError]
  ): Unit = {

    val fromBlockNumber = BlockNumber(from)
    val toBlockNumber   = BlockNumber(to)

    val test = for {
      response <- computeResponse(
                    stableNumberMappingStorage,
                    blockchainStorage,
                    stableBlockNumber,
                    Some(fromBlockNumber),
                    Some(toBlockNumber)
                  )
    } yield response shouldBe Right(
      CheckChainConsistencyResponse(
        fromBlockNumber,
        toBlockNumber,
        expectedErrors
      )
    )

    test.unsafeRunSync()
  }

  def computeResponse(
      stableNumberMappingStorage: StableNumberMappingStorage[IO],
      blockchainStorage: BlocksReader[IO],
      stableBlockNumber: Int,
      fromOpt: Option[BlockNumber],
      toOpt: Option[BlockNumber]
  ): ServiceResponse[CheckChainConsistencyResponse] =
    for {
      _            <- IO.unit
      stable        = BlockGenerators.obftBlockHeaderGen.sample.get.copy(number = BlockNumber(stableBlockNumber))
      currentBranch = CurrentBranch(stable, stable)
      implicit0(currentBranchSignal: SignallingRef[IO, CurrentBranch]) <-
        SignallingRef.of[IO, CurrentBranch](currentBranch)
      sanityController = new SanityControllerImpl[IO](
                           blockchainStorage,
                           (number: BlockNumber) => stableNumberMappingStorage.getBlockHash(number)
                         )
      request   = CheckChainConsistencyRequest(fromOpt, toOpt)
      response <- sanityController.checkChainConsistency(request)
    } yield response

  private def customStableNumberMappingStorage(blocks: List[ObftBlock]): StableNumberMappingStorage[IO] = {
    val m: StableNumberMappingStorage[IO] = stub[StableNumberMappingStorage[IO]]
    val number2block                      = blocks.map(block => (block.number, block)).toMap
    (m.getBlockHash _).when(*).onCall { n: BlockNumber =>
      IO(number2block.get(n).map(_.hash))
    }
    m
  }

  private def customBlocksReader(blocks: List[ObftBlock]): BlocksReader[IO] =
    customBlocksReader(blocks, blocks)

  private def customBlocksReader(
      availableHeaders: List[ObftBlock],
      availableBodies: List[ObftBlock]
  ): BlocksReader[IO] = {
    val storage: BlocksReader[IO] = stub[BlocksReader[IO]]
    val hash2headers              = availableHeaders.map(block => (block.hash, block.header)).toMap
    val hash2bodies               = availableBodies.map(block => (block.hash, block.body)).toMap
    (storage.getBlockHeader _).when(*).onCall { hash: BlockHash =>
      IO.delay(hash2headers.get(hash))
    }
    (storage.getBlockBody _).when(*).onCall { hash: BlockHash =>
      IO.delay(hash2bodies.get(hash))
    }
    storage
  }

  private def corruptedBlocksReader(
      storageHashes: List[BlockHash],
      corruptedBlocks: List[ObftBlock]
  ): BlocksReader[IO] = {
    val storage: BlocksReader[IO] = stub[BlocksReader[IO]]
    val hash2headers              = storageHashes.zip(corruptedBlocks).map { case (hash, block) => (hash, block.header) }.toMap
    val hash2bodies               = storageHashes.zip(corruptedBlocks).map { case (hash, block) => (hash, block.body) }.toMap
    (storage.getBlockHeader _).when(*).onCall { hash: BlockHash =>
      IO.delay(hash2headers.get(hash))
    }
    (storage.getBlockBody _).when(*).onCall { hash: BlockHash =>
      IO.delay(hash2bodies.get(hash))
    }
    storage
  }
}
