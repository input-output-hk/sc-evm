package io.iohk.scevm.sidechain

import cats.effect.IO
import io.iohk.scevm.domain.Slot
import io.iohk.scevm.testing.IOSupport
import io.iohk.scevm.utils.SystemTime
import io.iohk.scevm.utils.SystemTime.UnixTimestamp._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.duration._

class SidechainEpochDerivationSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with ScalaFutures
    with IOSupport {

  implicit val st: SystemTime[IO] = SystemTime.liveF[IO].ioValue

  "SidechainEpochDerivation" should {

    "return None on a timestamp before sidechain genesis" in {
      val epochDerivation =
        new SidechainEpochDerivationImpl(obftConfig = baseObftConf.copy(genesisTimestamp = 10000.millisToTs))

      epochDerivation.getSidechainEpoch(1000.millisToTs) shouldBe None
    }

    "return the right sidechain epoch by slot" in {
      val epochDerivation = new SidechainEpochDerivationImpl(
        obftConfig = baseObftConf.copy(genesisTimestamp = 0.millisToTs, epochDurationInSlot = 10)
      )

      epochDerivation.getSidechainEpoch(Slot(0)) shouldBe SidechainEpoch(0)
      epochDerivation.getSidechainEpoch(Slot(10)) shouldBe SidechainEpoch(1)
      epochDerivation.getSidechainEpoch(Slot(11)) shouldBe SidechainEpoch(1)
      epochDerivation.getSidechainEpoch(Slot(42)) shouldBe SidechainEpoch(4)
    }
  }

  def baseObftConf: ObftConfig =
    ObftConfig(
      genesisTimestamp = 0.millisToTs,
      stabilityParameter = 15,
      slotDuration = 10.seconds,
      epochDurationInSlot = 190
    )
}
