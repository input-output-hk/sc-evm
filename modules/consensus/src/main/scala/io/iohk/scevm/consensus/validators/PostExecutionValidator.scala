package io.iohk.scevm.consensus.validators

import cats.syntax.applicative.catsSyntaxApplicativeId
import cats.{Applicative, Show}
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.ByteStringUtils.RichByteString
import io.iohk.ethereum.utils.ByteUtils.or
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionResult
import io.iohk.scevm.consensus.validators.PostExecutionValidator._
import io.iohk.scevm.consensus.validators.PostExecutionValidatorImpl._
import io.iohk.scevm.domain.{BlockHash, ObftHeader, Receipt}
import io.iohk.scevm.exec.vm.WorldType
import io.iohk.scevm.ledger.BloomFilter
import io.iohk.scevm.mpt.MptStorage

trait PostExecutionValidator[F[_]] {
  def validate(
      initialState: WorldType,
      header: ObftHeader,
      result: BlockExecutionResult
  ): F[Either[PostExecutionError, Unit]]
}

object PostExecutionValidator {

  final case class PostExecutionError(hash: BlockHash, message: String) {
    override def toString: String = PostExecutionError.show.show(this)
  }

  object PostExecutionError {
    implicit val show: Show[PostExecutionError] = e => s"Validation of block ${e.hash} failed: ${e.message}"
  }
}

class PostExecutionValidatorImpl[F[_]: Applicative] extends PostExecutionValidator[F] {

  override def validate(
      _initialState: WorldType,
      header: ObftHeader,
      result: BlockExecutionResult
  ): F[Either[PostExecutionError, Unit]] =
    (for {
      _ <- validateGasUsed(header, result.gasUsed)
      _ <- validateStateRootHash(header, result.worldState.stateRootHash)
      _ <- validateReceipts(header, result.receipts)
      _ <- validateLogBloom(header, result.receipts)
    } yield ()).pure

  /* Private methods below */

  private def validateGasUsed(header: ObftHeader, gasUsed: BigInt): Either[PostExecutionError, ObftHeader] =
    Either.cond(header.gasUsed == gasUsed, header, gasUsedError(header.hash, header.gasUsed, gasUsed))

  private def validateStateRootHash(
      header: ObftHeader,
      stateRootHash: ByteString
  ): Either[PostExecutionError, ObftHeader] =
    Either.cond(header.stateRoot == stateRootHash, header, stateRootError(header.hash, header.stateRoot, stateRootHash))

  private def validateReceipts(header: ObftHeader, receipts: Seq[Receipt]): Either[PostExecutionError, ObftHeader] = {
    val receiptsRoot = MptStorage.rootHash(receipts, Receipt.byteArraySerializable)
    Either.cond(receiptsRoot == header.receiptsRoot, header, receiptsHashError(header.hash))
  }

  private def validateLogBloom(header: ObftHeader, receipts: Seq[Receipt]): Either[PostExecutionError, ObftHeader] = {
    val logsBloomOr =
      if (receipts.isEmpty) BloomFilter.EmptyBloomFilter
      else ByteString(or(receipts.map(_.logsBloomFilter.toArray): _*))
    Either.cond(logsBloomOr == header.logsBloom, header, logBloomError(header.hash, header.logsBloom, logsBloomOr))
  }
}

object PostExecutionValidatorImpl {

  private def gasUsedError(hash: BlockHash, headerGasUsed: BigInt, computedGasUsed: BigInt): PostExecutionError =
    PostExecutionError(hash, s"block has invalid gas used, expected=$headerGasUsed but computed=$computedGasUsed")

  private def logBloomError(
      hash: BlockHash,
      headerLogBloom: ByteString,
      computedLogBloom: ByteString
  ): PostExecutionError =
    PostExecutionError(
      hash,
      s"block has invalid log bloom, expected=${headerLogBloom.toHex} but computed=${computedLogBloom.toHex}"
    )

  private def receiptsHashError(hash: BlockHash): PostExecutionError =
    PostExecutionError(hash, s"block has invalid receipts root")

  private def stateRootError(
      hash: BlockHash,
      headerStateRoot: ByteString,
      computedStateRoot: ByteString
  ): PostExecutionError =
    PostExecutionError(
      hash,
      s"Block has invalid state root hash, expected=${headerStateRoot.toHex} but computed=${computedStateRoot.toHex}"
    )
}
