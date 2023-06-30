package io.iohk.scevm.observer

import doobie.ConnectionIO
import doobie.syntax.all._
import doobie.util.meta.Meta
import io.iohk.bytes.ByteString

object DbSyncRepository {

  def sumTotalMintedAmount(asset: Asset, fromBlock: Long, untilBlock: Long): ConnectionIO[(Long, Long)] =
    sql"""SELECT
         |   COALESCE(SUM(mtm.quantity), 0),
         |   COALESCE(COUNT(mtm.tx_id), 0)
         | FROM ma_tx_mint mtm
         | INNER JOIN multi_asset ma       ON ma.id = mtm.ident
         | INNER JOIN tx origin_tx         ON mtm.tx_id  = origin_tx.id
         | INNER JOIN block origin_block   ON origin_tx.block_id = origin_block.id
         | LEFT JOIN redeemer r            ON r.tx_id = mtm.tx_id
         | LEFT JOIN redeemer_data rd      ON rd.id = r.redeemer_data_id
         | WHERE ma."policy" = ${asset.policyId} AND
         |       ma.name = ${asset.name} AND
         |       origin_block.block_no >= ${fromBlock} AND
         |       origin_block.block_no < ${untilBlock} AND
         |       mtm.quantity >= 0
       """.stripMargin
      .query[(Long, Long)]
      .unique

  def sumTotalBurnedAmount(asset: Asset, fromBlock: Long, untilBlock: Long): ConnectionIO[(Long, Long)] =
    sql"""SELECT
         |   COALESCE(SUM(mtm.quantity), 0),
         |   COALESCE(COUNT(mtm.tx_id), 0)
         | FROM ma_tx_mint mtm
         | INNER JOIN multi_asset ma       ON ma.id = mtm.ident
         | INNER JOIN tx origin_tx         ON mtm.tx_id  = origin_tx.id
         | INNER JOIN block origin_block   ON origin_tx.block_id = origin_block.id
         | LEFT JOIN redeemer r            ON r.tx_id = mtm.tx_id
         | LEFT JOIN redeemer_data rd      ON rd.id = r.redeemer_data_id
         | WHERE ma."policy" = ${asset.policyId} AND
         |       ma.name = ${asset.name} AND
         |       origin_block.block_no >= ${fromBlock} AND
         |       origin_block.block_no < ${untilBlock} AND
         |       mtm.quantity < 0
       """.stripMargin
      .query[(Long, Long)]
      .unique

  implicit val byteStringMeta: Meta[ByteString] =
    Meta.ByteArrayMeta.timap(bytes => ByteString(bytes))(_.toArray)
}

final case class Asset(policyId: ByteString, name: ByteString)
