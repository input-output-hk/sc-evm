package io.iohk.scevm.db.storage

import cats.effect.Sync
import cats.syntax.all._
import cats.{Applicative, FlatMap}
import io.iohk.scevm.domain.BlockNumber._
import io.iohk.scevm.domain.{BlockHash, BlockNumber}

trait GetByNumberService[F[_]] {
  def getHashByNumber(branchTip: BlockHash)(number: BlockNumber): F[Option[BlockHash]]
}

object GetByNumberService {
  def apply[F[_]](implicit ev: GetByNumberService[F]): GetByNumberService[F] = ev
}

class GetByNumberServiceImpl[F[_]: Sync](
    blocksReader: BlocksReader[F],
    stableNumberMappingStorage: StableNumberMappingStorage[F]
) extends GetByNumberService[F] {
  def getHashByNumber(branchTip: BlockHash)(number: BlockNumber): F[Option[BlockHash]] =
    getFromStableMapping(number)
      .flatMap[Option[BlockHash]] {
        case Some(hash) => Applicative[F].pure(Some(hash))
        case None       => findNumberInBranch(branchTip, number)
      }

  private def getFromStableMapping(number: BlockNumber): F[Option[BlockHash]] =
    stableNumberMappingStorage.getBlockHash(number)

  private def findNumberInBranch(from: BlockHash, to: BlockNumber): F[Option[BlockHash]] =
    FlatMap[F].tailRecM[BlockHash, Option[BlockHash]](from) { hash =>
      blocksReader.getBlockHeader(hash).map {
        case Some(header) if header.number > to  => Either.left(header.parentHash)
        case Some(header) if header.number == to => Either.right(Some(header.hash))
        case _                                   => Either.right(None)
      }
    }
}
