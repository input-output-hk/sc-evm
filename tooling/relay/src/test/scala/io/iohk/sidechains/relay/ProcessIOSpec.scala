package io.iohk.sidechains.relay

import munit.CatsEffectSuite

import scala.concurrent.duration.FiniteDuration

class ProcessIOSpec extends CatsEffectSuite {
  test("Returns standard output of the command") {
    ProcessIO.execute(List("echo", "kopytko")).map(_.trim).assertEquals("kopytko")
  }

  test("Doesn't wait for timed out process execution") {
    ProcessIO
      .execute(List("sleep", "300"), FiniteDuration(1, "s"))
      .attempt
      .map(_.left.map(_.getMessage))
      .assertEquals(Left("Command failed: java.util.concurrent.TimeoutException: 1 second"))
  }
}
