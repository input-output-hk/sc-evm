package io.iohk.scevm.rpc.controllers

import io.iohk.scevm.domain.BlockNumber

sealed trait BlockParam
sealed trait EthBlockParam      extends BlockParam
sealed trait ExtendedBlockParam extends BlockParam

object BlockParam {
  final case class ByNumber(n: BlockNumber) extends EthBlockParam with ExtendedBlockParam
  final case object Latest                  extends EthBlockParam with ExtendedBlockParam
  final case object Pending                 extends EthBlockParam with ExtendedBlockParam
  final case object Earliest                extends EthBlockParam with ExtendedBlockParam
  final case object Stable                  extends ExtendedBlockParam
}
