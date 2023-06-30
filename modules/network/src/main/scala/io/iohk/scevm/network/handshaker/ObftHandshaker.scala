package io.iohk.scevm.network.handshaker

import cats.effect.IO

object ObftHandshaker {
  def apply(handshakerConfiguration: ObftHandshakerConfig[IO]): HandshakerState[IO] =
    HelloExchangeState(handshakerConfiguration)
}
