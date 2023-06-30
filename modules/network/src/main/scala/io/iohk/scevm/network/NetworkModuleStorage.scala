package io.iohk.scevm.network

import cats.data.OptionT
import cats.effect.Sync
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.scevm.db.storage._
import io.iohk.scevm.domain.{BlockHash, BlockNumber, ObftBlock, ObftBody, ObftHeader, Receipt}
import io.iohk.scevm.mpt.Node
import io.iohk.scevm.mpt.Node.Hash

trait NetworkModuleStorage[F[_]] extends BlocksWriter[F] with BlocksReader[F] {
  def getNode(nodeHash: Node.Hash): F[Option[ByteString]]

  def getReceiptsByHash(hash: BlockHash): F[Option[Seq[Receipt]]]

  def blockHeaders(
      branchTip: ObftHeader
  )(blockNumber: BlockNumber, skip: Int, limit: Int, reverse: Boolean): F[List[ObftHeader]]

  def getBlockHeader(hash: BlockHash): F[Option[ObftHeader]]

  def getBlockBody(hash: BlockHash): F[Option[ObftBody]]

  def getHeaderByNumber(tip: ObftHeader, number: BlockNumber): F[Option[ObftHeader]]

  def getStateStorage: StateStorage

  def getCodeStorage: EvmCodeStorage

  def getReceiptStorage: ReceiptStorage[F]

  def getBranchProvider: BranchProvider[F]
}

class NetworkModuleStorageImpl[F[_]: Sync](
    stateStorage: StateStorage,
    evmCodeStorage: EvmCodeStorage,
    receiptStorage: ReceiptStorage[F],
    blocksReader: BlocksReader[F],
    getByNumberService: GetByNumberService[F],
    blocksWriter: BlocksWriter[F],
    branchProvider: BranchProvider[F]
) extends NetworkModuleStorage[F] {
  override def getNode(nodeHash: Hash): F[Option[ByteString]] =
    for {
      maybeHashWithState   <- Sync[F].delay(getStateStorage.getNode(nodeHash).map(node => ByteString(node.encode)))
      maybeHashWithEmvCode <- Sync[F].delay(getCodeStorage.get(nodeHash))
    } yield maybeHashWithState.orElse(maybeHashWithEmvCode)

  override def getReceiptsByHash(hash: BlockHash): F[Option[Seq[Receipt]]] =
    receiptStorage.getReceiptsByHash(hash)

  override def blockHeaders(
      tipHeader: ObftHeader
  )(blockNumber: BlockNumber, skip: Int, limit: Int, reverse: Boolean): F[List[ObftHeader]] =
    (for {
      headersByHash <- OptionT.liftF(
                         NetworkModuleStorageImpl.getHashesByRange(getByNumberService)(tipHeader)(
                           blockNumber,
                           skip,
                           limit,
                           reverse
                         )
                       )
      blockHeaders <- headersByHash.traverse(h => OptionT(blocksReader.getBlockHeader(h)))
    } yield blockHeaders).getOrElse(List.empty)

  override def getBlockBody(hash: BlockHash): F[Option[ObftBody]] =
    blocksReader.getBlockBody(hash)

  override def getBlockHeader(hash: BlockHash): F[Option[ObftHeader]] =
    blocksReader.getBlockHeader(hash)

  override def insertHeader(header: ObftHeader): F[Unit] = blocksWriter.insertHeader(header)

  override def insertBody(blockHash: BlockHash, body: ObftBody): F[Unit] = blocksWriter.insertBody(blockHash, body)

  override def getBlock(hash: BlockHash): F[Option[ObftBlock]] = blocksReader.getBlock(hash)

  override def getHeaderByNumber(tip: ObftHeader, number: BlockNumber): F[Option[ObftHeader]] =
    (for {
      hash   <- OptionT(getByNumberService.getHashByNumber(tip.hash)(number))
      header <- OptionT(getBlockHeader(hash))
    } yield header).value

  override def getStateStorage: StateStorage = stateStorage

  override def getCodeStorage: EvmCodeStorage = evmCodeStorage

  override def getReceiptStorage: ReceiptStorage[F] = receiptStorage

  override def getBranchProvider: BranchProvider[F] = branchProvider
}

object NetworkModuleStorageImpl {

  /** Return a List of block hashes corresponding to the parameters.
    * The returned list will contains all the know hashes.
    * If a block is not available, it will be skipped.
    * For `reverse` false, the returned range is [blockNumber, blockNumber + (skip + 1) * limit].
    * For `reverse` true, the returned range is [blockNumber, blockNumber - (skip + 1) * limit].
    *
    * @param branchTip the tip of the chain to look into
    * @param blockNumber the first block number of the range
    * @param skip the number of blocks to skip between two list's entries
    * @param limit the maximum number of block's hashes to look for
    * @param reverse if true, the range will be in decreasing block number order
    */
  def getHashesByRange[F[_]: Sync](getByNumberService: GetByNumberService[F])(
      branchTip: ObftHeader
  )(blockNumber: BlockNumber, skip: Int, limit: Int, reverse: Boolean): F[List[BlockHash]] = {

    val range = List
      .iterate(blockNumber.value, limit)(_ + (skip + 1) * (if (reverse) -1 else 1))
      .filter(_ >= 0)
      .map(BlockNumber(_))

    range.traverse(getByNumberService.getHashByNumber(branchTip.hash)).map(_.flatten)
  }

}
