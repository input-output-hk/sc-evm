package io.iohk.scevm.rpc.controllers

import cats.Show
import cats.data.Validated.{Invalid, Valid}
import cats.data.{EitherT, NonEmptyList, Validated, ValidatedNel}
import cats.effect.Sync
import cats.implicits.{catsSyntaxApply, catsSyntaxValidatedId, showInterpolator, toFunctorOps}
import fs2.Pipe
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.db.storage.BlocksReader
import io.iohk.scevm.domain.BlockNumber._
import io.iohk.scevm.domain.{BlockHash, BlockNumber, ObftBlock, ObftBody, ObftHeader}
import io.iohk.scevm.rpc.ServiceResponseF
import io.iohk.scevm.rpc.controllers.SanityController._
import io.iohk.scevm.rpc.domain.JsonRpcError

trait SanityController[F[_]] {
  def checkChainConsistency(
      request: CheckChainConsistencyRequest
  ): ServiceResponseF[F, CheckChainConsistencyResponse]
}

object SanityController {

  final case class CheckChainConsistencyRequest(from: Option[BlockNumber], to: Option[BlockNumber])

  final case class CheckChainConsistencyResponse(
      from: BlockNumber,
      to: BlockNumber,
      errors: List[ChainConsistencyError]
  )

  sealed trait ChainConsistencyError
  object ChainConsistencyError {
    implicit val show: Show[ChainConsistencyError] = Show.show[ChainConsistencyError] {
      case MissingStableNumberMappingEntry(blockNumber) =>
        show"Block $blockNumber not found in StableNumberMapping storage"
      case InconsistentBlockNumber(blockNumber, block) =>
        show"StableNumberMapping($blockNumber) == ${block.hash} but BlockchainStorage(${block.hash}) == $block: block number doesn't match"
      case InvalidParentHash(block, parentBlock) =>
        show"Parent of ${block.fullToString} should be ${block.header.parentHash} but doesn't match ${parentBlock.header.idTag}"
      case InvalidParentNumber(block, parentBlock) =>
        show"${parentBlock.header.idTag} and ${block.header.idTag} numbers should be successive"
      case InconsistentBlockHash(blockHash, block) =>
        show"Inconsistent blockHash for $block: expected $blockHash"
      case MissingBlockHeader(blockHash) =>
        show"No block header found for $blockHash"
      case MissingBlockBody(blockHash) =>
        show"No block body found for $blockHash"
    }
  }
  final case class MissingStableNumberMappingEntry(blockNumber: BlockNumber)           extends ChainConsistencyError
  final case class MissingBlockHeader(blockHash: BlockHash)                            extends ChainConsistencyError
  final case class MissingBlockBody(blockHash: BlockHash)                              extends ChainConsistencyError
  final case class InconsistentBlockNumber(blockNumber: BlockNumber, block: ObftBlock) extends ChainConsistencyError
  final case class InconsistentBlockHash(blockHash: BlockHash, block: ObftBlock)       extends ChainConsistencyError
  final case class InvalidParentHash(block: ObftBlock, parentBlock: ObftBlock)         extends ChainConsistencyError
  final case class InvalidParentNumber(block: ObftBlock, parentBlock: ObftBlock)       extends ChainConsistencyError

  trait StableNumberMappingReader[F[_]] {
    def getBlockHash(number: BlockNumber): F[Option[BlockHash]]
  }
}

class SanityControllerImpl[F[_]: CurrentBranch.Signal: Sync](
    blockchainStorage: BlocksReader[F],
    stableNumberMappingReader: StableNumberMappingReader[F]
) extends SanityController[F] {

  private val GENESIS_BLOCK_NUMBER = BlockNumber(0)

  /** Perform a sanity check on the chain regarding its consistency.
    * For each block, it checks that:
    * - blockHash[x] is available in StableNumberMapping for a given block number x
    * - block[x] is available in blockchain storage for a given blockHash[x]
    * - and that block[x].number == x
    * - and that block[x].hash == blockHash[x]
    * - block[x].number + 1 == block[x + 1].number
    * - block[x].hash == block[x + 1].parent.hash
    * The range used is [from, to] if defined.
    * An undefined from is replaced by genesis.
    * An undefined to is replaced by stable.
    *
    * It returns the number of invalid entries.
    * If showDetail=true, it provides a list of errors.
    * If from is bigger than to, it returns an error.
    *
    * @param request the input request
    * @return the sanity check response
    */
  override def checkChainConsistency(
      request: CheckChainConsistencyRequest
  ): ServiceResponseF[F, CheckChainConsistencyResponse] =
    (for {
      (from, to) <- EitherT(getRange(request))
      invalidEntries <-
        EitherT.right[JsonRpcError](
          fs2.Stream
            .range(from, BlockNumber(to.value + 1))
            .evalMap(loadAndValidateBlock)
            .through(checkParentRelationship)
            .collect { case Validated.Invalid(error) =>
              error
            }
            .compile
            .toList
        )
    } yield {
      val flattenedErrors = invalidEntries.flatten(_.toList).distinct
      CheckChainConsistencyResponse(from, to, flattenedErrors)
    }).value

  /** Extract and sanitize the range from the request.
    * @see getSanitizedRange for applied rules on range.
    * @param request the input request
    * @return a sanitized range, or an error
    */
  private def getRange(
      request: SanityController.CheckChainConsistencyRequest
  ): ServiceResponseF[F, (BlockNumber, BlockNumber)] =
    for {
      stableHeader <- CurrentBranch.stable[F]
      stableNumber  = stableHeader.number
    } yield {
      val (from, to) = getSanitizedRange(request, stableNumber)
      if (from.value > to.value) {
        Left(JsonRpcError.InvalidParams(s"from=$from should be lower or equals than to=$to"))
      } else {
        Right((from, to))
      }
    }

  /** Return the (from, to) tuple from the request.
    * Perform the following sanitization:
    * - when not defined, from is set to genesis (0)
    * - when not defined, to is set to stable
    * - from and to should be in the range [0; stableBlockNumber]
    * @param request the input request
    * @param stableBlockNumber the current stable block number
    * @return the sanitized (from, to) tuple
    */
  private def getSanitizedRange(
      request: SanityController.CheckChainConsistencyRequest,
      stableBlockNumber: BlockNumber
  ): (BlockNumber, BlockNumber) = {
    val from = request.from.map(inRange(GENESIS_BLOCK_NUMBER, stableBlockNumber)).getOrElse(GENESIS_BLOCK_NUMBER)
    val to   = request.to.map(inRange(GENESIS_BLOCK_NUMBER, stableBlockNumber)).getOrElse(stableBlockNumber)
    (from, to)
  }

  /** Keep a BlockNumber within a given range (inclusive).
    * If it's out of the range, return the corresponding bound.
    */
  private def inRange(lowerBound: BlockNumber, upperBound: BlockNumber)(inputBlockNumber: BlockNumber): BlockNumber = {
    require(lowerBound.value <= upperBound.value)
    if (inputBlockNumber.value < lowerBound.value) {
      lowerBound
    } else if (inputBlockNumber.value > upperBound.value) {
      upperBound
    } else {
      inputBlockNumber
    }
  }

  /** Load a block hash from stable number mapping storage. If missing, return an error
    */
  private def getBlockHash(blockNumber: BlockNumber): EitherT[F, ChainConsistencyError, BlockHash] =
    EitherT.fromOptionM(
      stableNumberMappingReader.getBlockHash(blockNumber),
      Sync[F].delay(MissingStableNumberMappingEntry(blockNumber))
    )

  /** Load a block corresponding to a given block number.
    * It performs a number of checks.
    * @param blockNumber the blockNumber to load
    * @return Valid(block) if everything is right, InvalidNel(errors) if not
    */
  private def loadAndValidateBlock(blockNumber: BlockNumber): F[ValidatedNel[ChainConsistencyError, ObftBlock]] = {

    val validation = (for {
      blockHash <- getBlockHash(blockNumber)
      header    <- EitherT.liftF[F, ChainConsistencyError, Option[ObftHeader]](blockchainStorage.getBlockHeader(blockHash))
      body      <- EitherT.liftF[F, ChainConsistencyError, Option[ObftBody]](blockchainStorage.getBlockBody(blockHash))
    } yield {

      // availability of stable number mapping is handled by the either wrapper

      // check that no missing parts wrt header / body
      val blockValidation = (header, body) match {
        case (None, Some(_)) => Validated.invalidNel(MissingBlockHeader(blockHash))
        case (Some(_), None) => Validated.invalidNel(MissingBlockBody(blockHash))
        case (None, None) =>
          Validated.invalid(NonEmptyList.of(MissingBlockHeader(blockHash), MissingBlockBody(blockHash)))
        case (Some(h), Some(b)) => Validated.valid(ObftBlock(h, b))
      }

      // check that the block number is matching the one from stableNumberMapping
      val blockNumberValidation = blockValidation.andThen { block =>
        Validated.condNel[ChainConsistencyError, BlockNumber](
          block.number == blockNumber,
          blockNumber,
          InconsistentBlockNumber(blockNumber, block)
        )
      }

      // check that the block hash is matching the requested one from blockchain storage
      val blockHashValidation = blockValidation.andThen { block =>
        Validated.condNel[ChainConsistencyError, BlockHash](
          block.hash == blockHash,
          blockHash,
          InconsistentBlockHash(blockHash, block)
        )
      }

      blockNumberValidation.productR(blockHashValidation).productR(blockValidation)
    }).value

    validation.map {
      case Left(consistencyError) => Validated.invalidNel(consistencyError)
      case Right(validated)       => validated
    }
  }

  private def checkParentRelationship
      : Pipe[F, ValidatedNel[ChainConsistencyError, ObftBlock], ValidatedNel[ChainConsistencyError, ObftBlock]] =
    _.mapAccumulate[Option[ObftBlock], ValidatedNel[ChainConsistencyError, ObftBlock]](None: Option[ObftBlock]) {
      case (Some(parentBlock), Valid(a)) => (Some(a), checkParentRelationship(parentBlock, a))
      case (_, Invalid(e))               => (None, e.invalid)
      case (None, v @ Valid(a))          => (Some(a), v)
    }
      .map(_._2)

  /** Check that the hashes and blocks numbers are valid between parent-child blocks
    */
  private def checkParentRelationship(
      parentBlock: ObftBlock,
      block: ObftBlock
  ): ValidatedNel[ChainConsistencyError, ObftBlock] = {
    val parentNumberValidation = Validated.condNel[ChainConsistencyError, ObftBlock](
      parentBlock.number.next == block.number,
      block,
      InvalidParentNumber(block, parentBlock)
    )

    val parentHashValidation = Validated.condNel(
      parentBlock.hash == block.header.parentHash,
      block,
      InvalidParentHash(block, parentBlock)
    )
    parentNumberValidation.productR(parentHashValidation)
  }

}
