package io.iohk.scevm.network

import scala.concurrent.duration.FiniteDuration

final case class RLPxConfig(
    waitForHandshakeTimeout: FiniteDuration,
    waitForTcpAckTimeout: FiniteDuration
)
