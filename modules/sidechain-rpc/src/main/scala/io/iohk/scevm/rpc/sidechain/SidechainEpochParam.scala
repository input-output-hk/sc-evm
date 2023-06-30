package io.iohk.scevm.rpc.sidechain

import io.iohk.scevm.sidechain.SidechainEpoch

sealed trait SidechainEpochParam

object SidechainEpochParam {
  final case object Latest                        extends SidechainEpochParam
  final case class ByEpoch(epoch: SidechainEpoch) extends SidechainEpochParam
}
