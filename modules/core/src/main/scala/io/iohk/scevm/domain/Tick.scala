package io.iohk.scevm.domain

import io.iohk.scevm.utils.SystemTime.UnixTimestamp

final case class Tick(slot: Slot, timestamp: UnixTimestamp)
