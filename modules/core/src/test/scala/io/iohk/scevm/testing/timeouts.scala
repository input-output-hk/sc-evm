package io.iohk.scevm.testing

import scala.concurrent.duration._

object timeouts {

  val shortTimeout: FiniteDuration    = 500.millis
  val normalTimeout: FiniteDuration   = 3.seconds
  val longTimeout: FiniteDuration     = 10.seconds
  val veryLongTimeout: FiniteDuration = 30.seconds

}
