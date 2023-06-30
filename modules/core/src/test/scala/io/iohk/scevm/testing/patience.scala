package io.iohk.scevm.testing

import akka.util.Timeout
import org.scalatest.concurrent.PatienceConfiguration

import scala.concurrent.duration._

import timeouts._

trait NodePatience { self: PatienceConfiguration =>
  implicit lazy val actorAskTimeout: Timeout =
    FiniteDuration(patienceConfig.timeout.length, patienceConfig.timeout.unit)
  implicit lazy val taskTimeout: Duration = actorAskTimeout.duration
}

trait NormalPatience extends NodePatience {
  self: PatienceConfiguration =>

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(
    timeout = scaled(normalTimeout),
    interval = scaled(50.millis)
  )
}

trait LongPatience extends NodePatience {
  self: PatienceConfiguration =>

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(
    timeout = scaled(longTimeout),
    interval = scaled(100.millis)
  )
}

trait VeryLongPatience extends NodePatience {
  self: PatienceConfiguration =>

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(
    timeout = scaled(veryLongTimeout),
    interval = scaled(200.millis)
  )
}
