package io.iohk.scevm.db.storage.genesis

import cats.effect.IO
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignature}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.db.dataSource.EphemDataSource
import io.iohk.scevm.db.storage.{ArchiveStateStorage, EvmCodeStorageImpl, NodeStorage}
import io.iohk.scevm.domain._
import io.iohk.scevm.storage.metrics.NoOpStorageMetrics
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.wordspec.AnyWordSpec

class ObftGenesisLoaderSpec extends AnyWordSpec with ScalaFutures with IOSupport with NormalPatience {
  "Loading genesis" should {
    "set the values in storage" in {
      val storageMetrics = NoOpStorageMetrics[IO]()
      val evmCodeStorage =
        new EvmCodeStorageImpl(EphemDataSource(), storageMetrics.readTimeForEvmCode, storageMetrics.writeTimeForEvmCode)
      val stateStorage = new ArchiveStateStorage(
        new NodeStorage(EphemDataSource(), storageMetrics.readTimeForNode, storageMetrics.writeTimeForNode)
      )
      lazy val genesisData: ObftGenesisData = {
        lazy val account: ObftGenesisAccount =
          ObftGenesisAccount(UInt256(BigInt(2).pow(200)), Some(ByteString.fromString("abcd")), None, Map.empty)
        ObftGenesisData(
          Address(0),
          0x7a1200,
          UnixTimestamp.fromSeconds(1604534400),
          Map(
            Address("075b073eaa848fc14de2fd9c2a18d97a4783d84c") -> account,
            Address("18f496690eb3fabb794a653be8097af5331c07b1") -> account,
            Address("1ff7fc39f7f4dc79c5867b9514d0e42607741384") -> account,
            Address("75b0f1c605223de2a013bf7f26d4cd93c63a5eb8") -> account,
            Address("7e3166074d45c3311908315da1af5f5a965b7ece") -> account
          ),
          Nonce.Zero
        )
      }

      val genesis = ObftGenesisLoader.load[IO](evmCodeStorage, stateStorage)(genesisData).ioValue

      val expectedGenesis = ObftBlock(
        header = ObftHeader(
          parentHash = BlockHash(Hex.decodeUnsafe("0000000000000000000000000000000000000000000000000000000000000000")),
          number = BlockNumber(0),
          slotNumber = Slot(0),
          beneficiary = genesisData.coinbase,
          stateRoot = Hex.decodeUnsafe("d5ded6d83a0649048ae7205485d060aaec0dd17031e5156de2bed6539fb4f1ab"),
          transactionsRoot = Hex.decodeUnsafe("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"),
          receiptsRoot = Hex.decodeUnsafe("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421"),
          logsBloom = Hex.decodeUnsafe(
            "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
          ),
          gasLimit = genesisData.gasLimit,
          gasUsed = 0,
          unixTimestamp = genesisData.timestamp,
          publicSigningKey = ECDSA.PublicKey.Zero,
          signature = ECDSASignature.apply(ByteString.empty, ByteString.empty, 0)
        ),
        body = ObftBody.empty
      )

      // test
      assert(genesis == expectedGenesis)
      genesisData.alloc.foreach { case (_, account) =>
        assert(evmCodeStorage.get(crypto.kec256(account.code.get)).isDefined)
      }
      assert(stateStorage.archivingStorage.get(genesis.header.stateRoot).isDefined)
    }
  }
}
