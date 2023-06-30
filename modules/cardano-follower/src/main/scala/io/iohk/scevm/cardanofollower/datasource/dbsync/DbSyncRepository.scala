package io.iohk.scevm.cardanofollower.datasource.dbsync

import com.typesafe.scalalogging.StrictLogging
import doobie.postgres.implicits._
import doobie.syntax.all._
import doobie.util.fragment.Fragment
import doobie.util.log._
import doobie.{ConnectionIO, Get, LogHandler}
import io.iohk.scevm.cardanofollower.datasource.MainchainDataRepository
import io.iohk.scevm.cardanofollower.datasource.MainchainDataRepository.DbIncomingCrossChainTransaction
import io.iohk.scevm.domain.Token
import io.iohk.scevm.plutus.Datum
import io.iohk.scevm.trustlesssidechain.cardano._

import scala.concurrent.duration.DurationInt

object DbSyncRepository extends MainchainDataRepository[ConnectionIO] with StrictLogging {

  override def getEpochNonce(epoch: MainchainEpoch): ConnectionIO[Option[EpochNonce]] =
    sql"SELECT nonce FROM epoch_param WHERE epoch_no = ${epoch.number}"
      .query[EpochNonce]
      .option

  override def getStakeDistribution(epoch: MainchainEpoch): ConnectionIO[Map[Blake2bHash28, Lovelace]] =
    sql"""SELECT ph.hash_raw, SUM(es.amount) FROM epoch_stake es
         | INNER JOIN pool_hash ph ON es.pool_id = ph.id
         | WHERE es.epoch_no = $epoch
         | GROUP BY ph.hash_raw""".stripMargin
      .query[(Blake2bHash28, Lovelace)]
      .toMap

  // scalastyle:off method.length
  /** List all Unspent Transaction Outputs after `unspentAtBlock` given block and originating after `createdAfterBlock`.
    *
    * Note that it does not mean that the transaction output is unspent currently but that it was
    * unspent after running the given block.
    *
    * Note 2: array_agg(concat_ws(...)) is used because doobie / postgres JDBC cannot handle neither array of hash32
    * nor array of txindex types.
    */
  override def getUtxosForAddress(
      address: MainchainAddress,
      unspentAtBlock: MainchainBlockNumber,
      createdAfterBlock: Option[MainchainBlockNumber] = None
  ): ConnectionIO[Vector[MainchainTxOutput]] =
    (sql"""SELECT
         |  origin_tx.hash as origin_tx_hash,
         |  tx_out.index as utxo_index,
         |  origin_block.block_no as tx_block_no,
         |  origin_block.slot_no as tx_slot_no,
         |  origin_block.epoch_no as tx_epoch_no,
         |  origin_tx.block_index as tx_block_index,
         |  tx_out.address,
         |  datum.value as datum,
         |  consuming_tx.hash as consuming_tx_hash,
         |  consuming_block.epoch_no as consuming_tx_epoch,
         |  consuming_block.slot_no as consuming_tx_slot,
         |  array_agg(concat_ws('#', encode(consumes_tx.hash, 'hex'), consumes_tx_in.tx_out_index)) as tx_inputs
         | FROM tx_out
         | INNER JOIN tx    origin_tx    ON tx_out.tx_id = origin_tx.id
         | INNER JOIN block origin_block ON origin_tx.block_id = origin_block.id
         | LEFT JOIN tx_in consumes_tx_in ON consumes_tx_in.tx_in_id = origin_tx.id
         | LEFT JOIN tx_out consumes_tx_out ON consumes_tx_out.tx_id = consumes_tx_in.tx_out_id AND consumes_tx_in.tx_out_index = consumes_tx_out.index
         | LEFT JOIN tx consumes_tx ON consumes_tx.id = consumes_tx_out.tx_id
         | LEFT JOIN datum                 ON tx_out.data_hash = datum.hash
         | LEFT JOIN tx_in consuming_tx_in ON tx_out.tx_id = consuming_tx_in.tx_out_id AND tx_out.index = consuming_tx_in.tx_out_index
         | LEFT JOIN tx    consuming_tx    ON consuming_tx_in.tx_in_id  = consuming_tx.id
         | LEFT JOIN block consuming_block ON consuming_tx.block_id = consuming_block.id
         | WHERE
         |  tx_out.address = $address
         |  AND origin_block.block_no <= $unspentAtBlock
         |  AND (consuming_tx_in.id IS NULL OR consuming_block.block_no > $unspentAtBlock)
         |""".stripMargin ++
      createdAfterBlock.fold(Fragment.empty)(b => fr"AND origin_block.block_no > $b") ++
      sql"""GROUP BY (
           |  origin_tx_hash,
           |  utxo_index,
           |  tx_block_no,
           |  tx_slot_no,
           |  tx_epoch_no,
           |  tx_block_index,
           |  tx_out.address,
           |  datum,
           |  consuming_tx_hash,
           |  consuming_tx_epoch,
           |  consuming_tx_slot
           |  )""".stripMargin)
      .query[
        (
            MainchainTxHash,
            Int,
            MainchainBlockNumber,
            MainchainSlot,
            MainchainEpoch,
            Int,
            MainchainAddress,
            Option[Datum],
            Option[SpentByInfo],
            List[UtxoId]
        )
      ]
      .map {
        case (
              txHash,
              index,
              blockNumber,
              slotNumber,
              epochNumber,
              txIndexInBlock,
              mainchainAddress,
              datum,
              spentBy,
              txInputs
            ) =>
          MainchainTxOutput(
            id = UtxoId(txHash = txHash, index = index),
            blockNumber = blockNumber,
            slotNumber = slotNumber,
            epochNumber = epochNumber,
            txIndexInBlock = txIndexInBlock,
            address = mainchainAddress,
            datum = datum,
            spentBy = spentBy,
            txInputs
          )
      }
      .to[Vector]
  // scalastyle:on

  override def getLatestBlockForSlot(
      slot: MainchainSlot
  ): ConnectionIO[Option[MainchainBlockInfo]] =
    sql"""SELECT
         |  block.block_no,
         |  block.hash,
         |  block.epoch_no,
         |  block.slot_no,
         |  block.time
         |FROM
         |  block
         |WHERE block.slot_no <= $slot
         |ORDER BY block.slot_no DESC
         |LIMIT 1
         |""".stripMargin
      .query[MainchainBlockInfo]
      .option

  override def getLatestBlockInfo: ConnectionIO[Option[MainchainBlockInfo]] =
    sql"""SELECT
         |  block.block_no,
         |  block.hash,
         |  block.epoch_no,
         |  block.slot_no,
         |  block.time
         |FROM
         |  block
         |WHERE block.block_no IS NOT NULL
         |ORDER BY block.block_no DESC
         |LIMIT 1
       """.stripMargin
      .query[MainchainBlockInfo]
      .option

  override def getBlockInfoForNumber(blockNumber: MainchainBlockNumber): ConnectionIO[Option[MainchainBlockInfo]] =
    sql"""SELECT
         |  block.block_no,
         |  block.hash,
         |  block.epoch_no,
         |  block.slot_no,
         |  block.time
         |FROM
         |  block
         |WHERE block.block_no = $blockNumber
         |LIMIT 1
       """.stripMargin
      .query[MainchainBlockInfo]
      .option

  override def getSlotForTxHash(txHash: MainchainTxHash): ConnectionIO[Option[MainchainSlot]] =
    sql"""SELECT block.slot_no FROM block
         | INNER JOIN tx ON tx.block_id = block.id
         | WHERE tx.hash = $txHash""".stripMargin
      .query[MainchainSlot]
      .option

  override def getCrossChainTransactions(
      fromSlot: MainchainSlot,
      blockUpperBound: MainchainBlockNumber,
      fuelAsset: Asset
  ): ConnectionIO[Vector[DbIncomingCrossChainTransaction]] =
    sql"""SELECT
         | rd.value AS redeemer,
         | mtm.quantity AS value,
         | tx.hash as tx_hash,
         | b.block_no as block_no
         | FROM ma_tx_mint mtm
         | INNER JOIN multi_asset ma ON ma.id = mtm.ident AND ma.policy = ${fuelAsset.policy} AND ma.name = ${fuelAsset.name}
         | INNER JOIN redeemer r ON r.tx_id = mtm.tx_id
         | INNER JOIN redeemer_data rd on rd.id = r.redeemer_data_id
         | INNER JOIN tx ON tx.id = mtm.tx_id
         | INNER JOIN block b ON tx.block_id = b.id
         | WHERE b.slot_no >= $fromSlot AND b.block_no <= $blockUpperBound AND mtm.quantity < 0 AND r.purpose = 'mint'
         | ORDER BY (b.block_no, tx.block_index)
         |""".stripMargin
      .query[(Datum, Token, MainchainTxHash, MainchainBlockNumber)]
      .map { case (datum, quantity, txHash, block) =>
        DbIncomingCrossChainTransaction(datum, quantity, txHash, block)
      }
      .to[Vector]

  override def getNftUtxo(nft: Asset): ConnectionIO[Option[NftTxOutput]] =
    sql"""SELECT
         |   origin_tx.hash        AS origin_tx_hash,
         |   tx_out.index          AS utxo_index,
         |   origin_block.epoch_no AS tx_epoch_no,
         |   origin_block.block_no AS tx_block_no,
         |   origin_block.slot_no  AS tx_slot_no,
         |   origin_tx.block_index AS tx_block_index,
         |   datum.value           AS datum
         | FROM ma_tx_out
         | INNER JOIN multi_asset          ON ma_tx_out.ident = multi_asset.id
         | INNER JOIN tx_out               ON ma_tx_out.tx_out_id = tx_out.id
         | INNER JOIN tx origin_tx         ON tx_out.tx_id = origin_tx.id
         | INNER JOIN block origin_block   ON origin_tx.block_id = origin_block.id
         | LEFT JOIN datum                 ON tx_out.data_hash = datum.hash
         | LEFT JOIN tx_in consuming_tx_in ON tx_out.tx_id = consuming_tx_in.tx_out_id AND tx_out.index = consuming_tx_in.tx_out_index
         | WHERE multi_asset."policy" = ${nft.policy}
         |   AND name = ${nft.name}
         |   AND consuming_tx_in IS NULL
         | LIMIT 1;
       """.stripMargin
      .query[(MainchainTxHash, Int, MainchainEpoch, MainchainBlockNumber, MainchainSlot, Int, Option[Datum])]
      .map { case (txHash, index, epoch, blockNumber, slot, txIndexWithinBlock, datum) =>
        NftTxOutput(
          id = UtxoId(txHash, index),
          epoch = epoch,
          blockNumber = blockNumber,
          slot = slot,
          txIndexWithinBlock = txIndexWithinBlock,
          datum = datum
        )
      }
      .option

  override def getLatestMintAction(policyId: PolicyId): ConnectionIO[Option[MintAction]] =
    sql"""SELECT
         |   origin_tx.hash,
         |   ma."name",
         |   origin_block.block_no,
         |   origin_tx.block_index,
         |   rd.value
         | FROM ma_tx_mint mtm
         | INNER JOIN multi_asset ma       ON ma.id = mtm.ident
         | INNER JOIN tx origin_tx         ON mtm.tx_id  = origin_tx.id
         | INNER JOIN block origin_block   ON origin_tx.block_id = origin_block.id
         | LEFT JOIN redeemer r            ON r.tx_id = mtm.tx_id
         | LEFT JOIN redeemer_data rd      ON rd.id = r.redeemer_data_id
         | WHERE ma."policy" = $policyId
         | ORDER BY (origin_block.block_no, origin_tx.block_index) DESC
         | LIMIT 1;
       """.stripMargin
      .query[(MainchainTxHash, AssetName, MainchainBlockNumber, Int, Option[Datum])]
      .map { case (txHash, assetName, blockNumber, txIndexWithinBlock, redeemer) =>
        MintAction(
          txHash = txHash,
          blockNumber = blockNumber,
          assetName = assetName,
          txIndexInBlock = txIndexWithinBlock,
          redeemer = redeemer
        )
      }
      .option

  /** Logs the SQL queries which are slow or end up in an exception.
    */
  implicit private val doobieLogHandler: LogHandler = {
    val SlowThreshold = 200.millis
    LogHandler {
      case Success(sql, _, exec, processing) =>
        if (exec > SlowThreshold || processing > SlowThreshold) {
          logger.debug(s"Slow query (execution: $exec, processing: $processing): $sql")
        }
      case ProcessingFailure(sql, args, exec, processing, failure) =>
        logger.error(
          s"Error while querying cardano-db-sync database. Processing failure (execution: $exec, processing: $processing): \n" +
            s"$sql | args: $args",
          failure
        )
      case ExecFailure(sql, args, exec, failure) =>
        logger.error(
          s"Error while querying cardano-db-sync database. Execution failure (execution: $exec): $sql | args: $args",
          failure
        )
    }
  }

  implicit private val utxoIdFromString: Get[List[UtxoId]] =
    implicitly[Get[List[String]]].tmap[List[UtxoId]](_.filter(_.nonEmpty).flatMap { s =>
      UtxoId.parse(s) match {
        case Right(utxoId) => List(utxoId)
        case Left(_)       => throw new IllegalStateException(s"Query returned '$s' that couldn't be parsed to UtoxId")
      }
    })
}
