package io.iohk.scevm.network.forkid

import cats.effect.IO
import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{BlockHash, BlockNumber}
import org.bouncycastle.util.encoders.Hex
import org.scalatest.matchers.should._
import org.scalatest.wordspec.AnyWordSpec
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class ForkIdValidatorSpec extends AnyWordSpec with Matchers {
  import cats.effect.unsafe.implicits.global

  val ethGenesisHash: BlockHash = BlockHash(
    ByteString(
      Hex.decode("d4e56740f876aef8c010b86a40d5f56745a118d0906a34e69aec8c0db1cb8fa3")
    )
  )

  "ForkIdValidator" must {
    "correctly validate ETH peers" in {
      // latest fork at the time of writing those assertions (in the spec) was Petersburg
      val ethForksList: List[BlockNumber] =
        List(1150000, 1920000, 2463000, 2675000, 4370000, 7280000).map(BlockNumber(_))

      def validatePeer(head: BigInt, remoteForkId: ForkId) = {
        implicit val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
        ForkIdValidator
          .validatePeer[IO](ethGenesisHash, ethForksList)(BlockNumber(head), remoteForkId)
          .unsafeRunSync()
      }

      // Local is mainnet Petersburg, remote announces the same. No future fork is announced.
      validatePeer(7987396, ForkId(0x668db0afL, None)) shouldBe Connect

      // Local is mainnet Petersburg, remote announces the same. Remote also announces a next fork
      // at block 0xffffffff, but that is uncertain.
      validatePeer(7279999, ForkId(0xa00bc324L, Some(ForkIdValidator.MaxBlockNumber))) shouldBe Connect

      // Local is mainnet currently in Byzantium only (so it's aware of Petersburg), remote announces
      // also Byzantium, and it's also aware of Petersburg (e.g. updated node before the fork). We
      // don't know if Petersburg passed yet (will pass) or not.
      validatePeer(7279999, ForkId(0xa00bc324L, Some(BlockNumber(7280000)))) shouldBe Connect

      // Local is mainnet Petersburg, remote announces the same. Remote also announces a next fork
      // at block 0xffffffff, but that is uncertain.
      validatePeer(7987396, ForkId(0x668db0afL, Some(ForkIdValidator.MaxBlockNumber))) shouldBe Connect

      // Local is mainnet currently in Byzantium only (so it's aware of Petersburg), remote announces
      // also Byzantium, but it's not yet aware of Petersburg (e.g. non updated node before the fork).
      // In this case we don't know if Petersburg passed yet or not.
      validatePeer(7279999, ForkId(0xa00bc324L, None)) shouldBe Connect

      validatePeer(7279999, ForkId(0xa00bc324L, Some(BlockNumber(7280000)))) shouldBe Connect

      // Local is mainnet currently in Byzantium only (so it's aware of Petersburg), remote announces
      // also Byzantium, and it's also aware of some random fork (e.g. misconfigured Petersburg). As
      // neither forks passed at neither nodes, they may mismatch, but we still connect for now.
      validatePeer(7279999, ForkId(0xa00bc324L, Some(ForkIdValidator.MaxBlockNumber))) shouldBe Connect

      // Local is mainnet Petersburg, remote announces Byzantium + knowledge about Petersburg. Remote
      // is simply out of sync, accept.
      validatePeer(7987396, ForkId(0xa00bc324L, Some(BlockNumber(7280000)))) shouldBe Connect

      // Local is mainnet Petersburg, remote announces Spurious + knowledge about Byzantium. Remote
      // is definitely out of sync. It may or may not need the Petersburg update, we don't know yet.
      validatePeer(7987396, ForkId(0x3edd5b10L, Some(BlockNumber(4370000)))) shouldBe Connect

      // Local is mainnet Byzantium, remote announces Petersburg. Local is out of sync, accept.
      validatePeer(7279999, ForkId(0x668db0afL, None)) shouldBe Connect

      // Local is mainnet Spurious, remote announces Byzantium, but is not aware of Petersburg. Local
      // out of sync. Local also knows about a future fork, but that is uncertain yet.
      validatePeer(4369999, ForkId(0xa00bc324L, None)) shouldBe Connect

      // Local is mainnet Petersburg. remote announces Byzantium but is not aware of further forks.
      // Remote needs software update.
      validatePeer(7987396, ForkId(0xa00bc324L, None)) shouldBe ErrRemoteStale

      // Local is mainnet Petersburg, and isn't aware of more forks. Remote announces Petersburg +
      // 0xffffffff. Local needs software update, reject.
      validatePeer(7987396, ForkId(0x5cddc0e1L, None)) shouldBe ErrLocalIncompatibleOrStale

      // Local is mainnet Byzantium, and is aware of Petersburg. Remote announces Petersburg +
      // 0xffffffff. Local needs software update, reject.
      validatePeer(7279999, ForkId(0x5cddc0e1L, None)) shouldBe ErrLocalIncompatibleOrStale

      // Local is mainnet Petersburg, remote is Rinkeby Petersburg.
      validatePeer(7987396, ForkId(0xafec6b27L, None)) shouldBe ErrLocalIncompatibleOrStale

      // Local is mainnet Petersburg, far in the future. Remote announces Gopherium (non existing fork)
      // at some future block 88888888, for itself, but past block for local. Local is incompatible.
      //
      // This case detects non-upgraded nodes with majority hash power (typical Ropsten mess).
      validatePeer(88888888, ForkId(0x668db0afL, Some(BlockNumber(88888888)))) shouldBe ErrLocalIncompatibleOrStale

      // Local is mainnet Byzantium. Remote is also in Byzantium, but announces Gopherium (non existing
      // fork) at block 7279999, before Petersburg. Local is incompatible.
      validatePeer(7279999, ForkId(0xa00bc324L, Some(BlockNumber(7279999L)))) shouldBe ErrLocalIncompatibleOrStale
    }
  }
}