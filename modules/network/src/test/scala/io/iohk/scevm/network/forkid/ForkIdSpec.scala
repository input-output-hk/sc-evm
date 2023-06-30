package io.iohk.scevm.network.forkid

import cats.effect.IO
import io.iohk.ethereum.rlp._
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.{BlockchainConfig, ForkBlockNumbers}
import io.iohk.scevm.db.storage.genesis.ObftGenesisLoader
import io.iohk.scevm.domain.BlockNumber
import io.iohk.scevm.network.forkid.ForkId._
import io.iohk.scevm.testing.{IOSupport, NormalPatience, TestCoreConfigs}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should._
import org.scalatest.wordspec.AnyWordSpec

// TODO rewrite the test with a custom config with fork numbers
class ForkIdSpec extends AnyWordSpec with Matchers with ScalaFutures with IOSupport with NormalPatience {

  val config: BlockchainConfig = TestCoreConfigs.blockchainConfig.copy(
    forkBlockNumbers = ForkBlockNumbers(
      frontierBlockNumber = BlockNumber(0),
      homesteadBlockNumber = BlockNumber(1150000),
      eip150BlockNumber = BlockNumber(2463000),
      spuriousDragonBlockNumber = BlockNumber(2675000),
      byzantiumBlockNumber = BlockNumber(4370000),
      constantinopleBlockNumber = BlockNumber(7280000),
      petersburgBlockNumber = BlockNumber(7280000),
      istanbulBlockNumber = BlockNumber(9069000),
      berlinBlockNumber = BlockNumber(12244000),
      londonBlockNumber = BlockNumber(12965000)
    )
  )

  "ForkId" must {

    "gatherForks correctly" in {
      gatherForks(config) shouldBe
        List(1150000, 2463000, 2675000, 4370000, 7280000, 9069000, 12244000, 12965000)
    }

    "create correct ForkId for ETH mainnet blocks" in {
      val genesisHash          = ObftGenesisLoader.getGenesisHash[IO](config.genesisData).ioValue
      def create(head: BigInt) = ForkId.create(genesisHash, config)(BlockNumber(head))

      create(0) shouldBe ForkId(0xbed465c7L, Some(BlockNumber(1150000)))
      create(1149999) shouldBe ForkId(0xbed465c7L, Some(BlockNumber(1150000)))
      create(2462999) shouldBe ForkId(0x469c34e5L, Some(BlockNumber(2463000)))
      create(2463000) shouldBe ForkId(0x6ef56cfeL, Some(BlockNumber(2675000)))
      create(2674999) shouldBe ForkId(0x6ef56cfeL, Some(BlockNumber(2675000)))
      create(2675000) shouldBe ForkId(0x26f7e59eL, Some(BlockNumber(4370000)))
      create(4369999) shouldBe ForkId(0x26f7e59eL, Some(BlockNumber(4370000)))
      create(4370000) shouldBe ForkId(0x60579a22L, Some(BlockNumber(7280000)))
      create(7279999) shouldBe ForkId(0x60579a22L, Some(BlockNumber(7280000)))
      create(7280000) shouldBe ForkId(0x84f6351bL, Some(BlockNumber(9069000)))
      create(9068999) shouldBe ForkId(0x84f6351bL, Some(BlockNumber(9069000)))
      create(9069000) shouldBe ForkId(0x3cfe1a85L, Some(BlockNumber(12244000)))
      create(12965000) shouldBe ForkId(0x2e4a1028L, None)
    }
    // Here’s a couple of tests to verify the proper RLP encoding (since FORK_HASH is a 4 byte binary but FORK_NEXT is an 8 byte quantity):
    "be correctly encoded via rlp" in {
      roundTrip(ForkId(0, None), "c6840000000080")
      roundTrip(ForkId(0xdeadbeefL, Some(BlockNumber(0xbaddcafeL))), "ca84deadbeef84baddcafe")

      val maxUInt64 = (BigInt(0x7fffffffffffffffL) << 1) + 1
      maxUInt64.toByteArray shouldBe Array(0, -1, -1, -1, -1, -1, -1, -1, -1)
      val maxUInt32 = BigInt(0xffffffffL)
      maxUInt32.toByteArray shouldBe Array(0, -1, -1, -1, -1)

      roundTrip(ForkId(maxUInt32, Some(BlockNumber(maxUInt64))), "ce84ffffffff88ffffffffffffffff")
    }
  }

  private def roundTrip(forkId: ForkId, hex: String) = {
    encode(forkId.toRLPEncodable) shouldBe Hex.decodeAsArrayUnsafe(hex)
    decode[ForkId](Hex.decodeAsArrayUnsafe(hex)) shouldBe forkId
  }
}
