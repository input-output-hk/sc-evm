package io.iohk.scevm.network

final case class HostConfig(
    maxBlocksHeadersPerMessage: Int,
    maxBlocksBodiesPerMessage: Int,
    maxBlocksPerReceiptsMessage: Int,
    maxBlocksPerFullBlocksMessage: Int,
    maxStableHeadersPerMessage: Int,
    maxMptComponentsPerMessage: Int,
    parallelism: Int
)
