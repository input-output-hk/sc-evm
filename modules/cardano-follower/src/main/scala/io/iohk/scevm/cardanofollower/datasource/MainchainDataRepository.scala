package io.iohk.scevm.cardanofollower.datasource

import cats.Show
import io.iohk.scevm.domain.Token
import io.iohk.scevm.plutus.Datum
import io.iohk.scevm.trustlesssidechain.cardano._

import MainchainDataRepository.DbIncomingCrossChainTransaction

trait MainchainDataRepository[F[_]] {

  def getEpochNonce(epoch: MainchainEpoch): F[Option[EpochNonce]]

  def getStakeDistribution(epoch: MainchainEpoch): F[Map[Blake2bHash28, Lovelace]]

  /** List all Unspent Transaction Outputs after `unspentAtBlock` given block and originating after `createdAfterBlock`.
    *
    * Note that it does not mean that the transaction output is unspent currently but that it was
    * unspent after running the given block.
    *
    * Note 2: array_agg(concat_ws(...)) is used because doobie / postgres JDBC cannot handle neither array of hash32
    * nor array of txindex types.
    */
  def getUtxosForAddress(
      address: MainchainAddress,
      unspentAtBlock: MainchainBlockNumber,
      createdAfterBlock: Option[MainchainBlockNumber] = None
  ): F[Vector[MainchainTxOutput]]

  def getLatestBlockForSlot(
      slot: MainchainSlot
  ): F[Option[MainchainBlockInfo]]

  def getLatestBlockInfo: F[Option[MainchainBlockInfo]]

  def getBlockInfoForNumber(blockNumber: MainchainBlockNumber): F[Option[MainchainBlockInfo]]

  def getSlotForTxHash(txHash: MainchainTxHash): F[Option[MainchainSlot]]

  def getCrossChainTransactions(
      fromSlot: MainchainSlot,
      blockUpperBound: MainchainBlockNumber,
      fuelAsset: Asset
  ): F[Vector[DbIncomingCrossChainTransaction]]

  def getNftUtxo(nft: Asset): F[Option[NftTxOutput]]

  def getLatestMintAction(policyId: PolicyId): F[Option[MintAction]]
}

object MainchainDataRepository {
  final case class DbIncomingCrossChainTransaction(
      datum: Datum,
      value: Token,
      txHash: MainchainTxHash,
      blockNumber: MainchainBlockNumber
  )

  object DbIncomingCrossChainTransaction {
    implicit val show: Show[DbIncomingCrossChainTransaction] = cats.derived.semiauto.show
  }
}
