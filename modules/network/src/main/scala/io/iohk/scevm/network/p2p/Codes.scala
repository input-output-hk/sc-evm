package io.iohk.scevm.network.p2p

object Codes {
  val StatusCode: Int                     = ProtocolVersions.SubProtocolOffset + 0x00
  val NewBlockHashesCode: Int             = ProtocolVersions.SubProtocolOffset + 0x01
  val SignedTransactionsCode: Int         = ProtocolVersions.SubProtocolOffset + 0x02
  val GetBlockHeadersCode: Int            = ProtocolVersions.SubProtocolOffset + 0x03
  val BlockHeadersCode: Int               = ProtocolVersions.SubProtocolOffset + 0x04
  val GetBlockBodiesCode: Int             = ProtocolVersions.SubProtocolOffset + 0x05
  val BlockBodiesCode: Int                = ProtocolVersions.SubProtocolOffset + 0x06
  val NewBlockCode: Int                   = ProtocolVersions.SubProtocolOffset + 0x07
  val NewPooledTransactionHashesCode: Int = ProtocolVersions.SubProtocolOffset + 0x08
  val GetPooledTransactionsCode: Int      = ProtocolVersions.SubProtocolOffset + 0x09
  val PooledTransactionsCode: Int         = ProtocolVersions.SubProtocolOffset + 0x0a
  val GetNodeDataCode: Int                = ProtocolVersions.SubProtocolOffset + 0x0d
  val NodeDataCode: Int                   = ProtocolVersions.SubProtocolOffset + 0x0e
  val GetReceiptsCode: Int                = ProtocolVersions.SubProtocolOffset + 0x0f
  val ReceiptsCode: Int                   = ProtocolVersions.SubProtocolOffset + 0x10
  val GetStableHeadersCode: Int           = ProtocolVersions.SubProtocolOffset + 0x11
  val StableHeadersCode: Int              = ProtocolVersions.SubProtocolOffset + 0x12
  val GetFullBlocksCode: Int              = ProtocolVersions.SubProtocolOffset + 0x13
  val FullBlocksCode: Int                 = ProtocolVersions.SubProtocolOffset + 0x14
}
