package io.iohk.scevm.config

import cats.effect.IO
import io.iohk.scevm.domain.{Address, Nonce, ObftGenesisAccount, ObftGenesisData, UInt256}
import io.iohk.scevm.testing.NormalPatience
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

class GenesisParsingSpec extends AsyncWordSpec with ScalaFutures with NormalPatience with Matchers {

  import cats.effect.unsafe.implicits.global

  val account: ObftGenesisAccount = ObftGenesisAccount(UInt256(BigInt(2).pow(200)), None, None, Map.empty)
  val genesis: ObftGenesisData = ObftGenesisData(
    Address(0),
    0x7a1200,
    (BigInt("5fa34080", 16).toLong * 1000).millisToTs,
    Map(
      Address("075b073eaa848fc14de2fd9c2a18d97a4783d84c") -> account,
      Address("18f496690eb3fabb794a653be8097af5331c07b1") -> account,
      Address("1ff7fc39f7f4dc79c5867b9514d0e42607741384") -> account,
      Address("75b0f1c605223de2a013bf7f26d4cd93c63a5eb8") -> account,
      Address("7e3166074d45c3311908315da1af5f5a965b7ece") -> account
    ),
    Nonce.Zero
  )

  "GenesisDataLoader" should {
    "be able to parse genesis block from a config file" in {
      GenesisDataFileLoader
        .loadGenesisDataFile[IO]("blockchain/valid-genesis.json")
        .map(_ shouldEqual genesis)
        .unsafeToFuture()
    }

    "should fail if the genesis block is defined but invalid" in {
      recoverToSucceededIf[org.json4s.MappingException] {
        GenesisDataFileLoader.loadGenesisDataFile[IO]("blockchain/invalid-genesis.json").unsafeToFuture()
      }
    }
  }
}
