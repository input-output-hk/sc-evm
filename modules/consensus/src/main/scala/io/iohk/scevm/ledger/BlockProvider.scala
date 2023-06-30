package io.iohk.scevm.ledger

import cats.MonadThrow
import cats.data.OptionT
import cats.syntax.all._
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.db.storage.ObftAppStorage.MissingStable
import io.iohk.scevm.db.storage.{BlocksReader, GetByNumberService}
import io.iohk.scevm.domain.{BlockHash, BlockNumber, ObftBlock, ObftBody, ObftHeader}

import BlockProvider.MissingBlockBody

trait BlockProvider[F[_]] {

  /** Returns the block by its number. Blocks which are part of an unstable branch,
    * will be resolved against the current best branch.
    */
  def getBlock(number: BlockNumber): F[Option[ObftBlock]]

  def getBlock(hash: BlockHash): F[Option[ObftBlock]]

  def getHeader(number: BlockNumber): F[Option[ObftHeader]]

  def getHeader(hash: BlockHash): F[Option[ObftHeader]]

  def getBody(number: BlockNumber): F[Option[ObftBody]]

  def getBody(hash: BlockHash): F[Option[ObftBody]]

  /** Returns the best block from the currently best branch
    */
  def getBestBlock: F[ObftBlock]

  /** Returns the best block header from the currently best branch
    */
  def getBestBlockHeader: F[ObftHeader]

  /** Returns the best block from the currently best branch
    */
  def getStableBlock: F[ObftBlock]

  /** Returns the best block header from the currently best branch
    */
  def getStableBlockHeader: F[ObftHeader]

  def getHashByNumber(branchTip: ObftHeader)(number: BlockNumber): F[Option[BlockHash]]
}

object BlockProvider {
  final case class MissingBlockBody(hash: BlockHash)
      extends Exception(s"missing block body in storage: hash=${hash.toHex}")
}

class BlockProviderImpl[F[_]: MonadThrow: CurrentBranch.Signal](
    blocksReader: BlocksReader[F],
    getByNumberService: GetByNumberService[F]
) extends BlockProvider[F] {
  def getBlock(number: BlockNumber): F[Option[ObftBlock]] =
    (for {
      header <- OptionT(getHeader(number))
      body   <- OptionT(getBody(header.hash))
    } yield ObftBlock(header, body)).value

  def getBestBlock: F[ObftBlock] =
    for {
      header    <- getBestBlockHeader
      maybeBody <- getBody(header.hash)
      body      <- MonadThrow[F].fromOption(maybeBody, MissingBlockBody(header.hash))
    } yield ObftBlock(header, body)

  override def getBestBlockHeader: F[ObftHeader] = CurrentBranch.best

  override def getBlock(hash: BlockHash): F[Option[ObftBlock]] = blocksReader.getBlock(hash)

  override def getHeader(number: BlockNumber): F[Option[ObftHeader]] =
    getHashByNumberInStable(number).flatMapF(blocksReader.getBlockHeader).value

  override def getHeader(hash: BlockHash): F[Option[ObftHeader]] = blocksReader.getBlockHeader(hash)

  override def getBody(number: BlockNumber): F[Option[ObftBody]] =
    getHashByNumberInStable(number).flatMapF(blocksReader.getBlockBody).value

  override def getBody(hash: BlockHash): F[Option[ObftBody]] = blocksReader.getBlockBody(hash)

  override def getStableBlock: F[ObftBlock] = for {
    header           <- getStableBlockHeader
    maybeStableBlock <- blocksReader.getBlock(header.hash)
    stableBlock      <- MonadThrow[F].fromOption(maybeStableBlock, MissingStable)
  } yield stableBlock

  override def getStableBlockHeader: F[ObftHeader] = CurrentBranch.stable[F]

  override def getHashByNumber(branchTip: ObftHeader)(number: BlockNumber): F[Option[BlockHash]] =
    getByNumberService.getHashByNumber(branchTip.hash)(number)

  private def getHashByNumberInStable(number: BlockNumber): OptionT[F, BlockHash] =
    OptionT.liftF(getBestBlockHeader).flatMapF(getHashByNumber(_)(number))
}
