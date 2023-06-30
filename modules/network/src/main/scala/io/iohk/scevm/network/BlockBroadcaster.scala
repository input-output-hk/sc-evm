package io.iohk.scevm.network

import io.iohk.scevm.domain.ObftBlock

trait BlockBroadcaster[F[_]] {
  def broadcastBlock(block: ObftBlock): F[Unit]
}
