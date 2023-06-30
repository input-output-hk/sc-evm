package io.iohk.scevm.sidechain.certificate

import cats.data.NonEmptyList
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.Token
import io.iohk.scevm.sidechain.SidechainFixtures
import io.iohk.scevm.sidechain.transactions._
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.testing
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class MerkleRootSignerSpec extends AnyWordSpec with ScalaCheckPropertyChecks {
  private val sidechainParams = SidechainFixtures.sidechainParams

  private val validatorKey =
    ECDSA.PrivateKey.fromHexUnsafe("c0fff366fb01ff80008a005701ff5d804527190021e90051c200ff802f9e467f")

  "should return signature for single transaction and no previous merkle root - snapshot test" in {
    val expectedSignature = Hex.decodeUnsafe(
      "9b34b7302c3481a9c96b96aef4083394a5d7f3ec90d889c318e2520be0e21fad61702d08360b9e7a1ac29712d33e1caf56525909c696b8a165b9e88bb03544791c"
    )
    val expectedRootHash = Hex.decodeUnsafe("7d3cf44a08fb0a5e142efec5c415d3530bc1c562ada168f542dd8a960a6e10e1")
    val signature =
      MerkleRootSigner.sign(
        None,
        sidechainParams,
        NonEmptyList.one(
          OutgoingTransaction(
            Token(123),
            OutgoingTxRecipient.decodeUnsafe("caadf8ad86e61a10f4b4e14d51acf1483dac59c9e90cbca5eda8b840"),
            OutgoingTxId(1L)
          )
        )
      )(
        validatorKey
      )
    assert(signature.signature.toBytes == expectedSignature)
    assert(signature.rootHash.value == expectedRootHash)
  }

  "should return signature for single transaction and some previous merkle root - snapshot test" in {
    val expectedSignature = Hex.decodeUnsafe(
      "bfa6d56006d745287413c059c3f856d657ff39ac929c2f1352945bacdd1883fb137e61d86b5423e9e8aba174dd949717500b3230fe9e5f90fcb7bf55735009f51c"
    )
    val expectedRootHash = Hex.decodeUnsafe("0b2bc80b74bba96817248ed6a980a55e1cf7da1e073d747c875197683532741b")
    val signature =
      MerkleRootSigner.sign(
        Some(RootHash(Hex.decodeUnsafe("1a9f9b8967b06ecf3b073f028ee2c2ffabe20cfe27c6eb154ccffbcbd8c45df4"))),
        sidechainParams,
        NonEmptyList.one(
          OutgoingTransaction(
            Token(123),
            OutgoingTxRecipient.decodeUnsafe("caadf8ad86e61a10f4b4e14d51acf1483dac59c9e90cbca5eda8b840"),
            OutgoingTxId(1L)
          )
        )
      )(
        validatorKey
      )
    assert(signature.signature.toBytes == expectedSignature)
    assert(signature.rootHash.value == expectedRootHash)
  }

  "should return signature for two transaction and some previous merkle root - snapshot test" in {
    val expectedSignature = Hex.decodeUnsafe(
      "cd97cd9ebb879e120d19496a6c7cb253eb5666193380a66b4e03528b7b11ec5f24cd5f1dc2183a7d51cd9c204ee6c85a879221377d6bb9cd3d0404ec62e0259f1b"
    )
    val expectedRootHash = Hex.decodeUnsafe("5205c99e0d99cb6feaf400e26a86f03c47188113e57c0c85f592fd0376108b6f")
    val signature =
      MerkleRootSigner.sign(
        Some(RootHash(Hex.decodeUnsafe("1a9f9b8967b06ecf3b073f028ee2c2ffabe20cfe27c6eb154ccffbcbd8c45df4"))),
        sidechainParams,
        NonEmptyList.of(
          OutgoingTransaction(
            Token(123),
            OutgoingTxRecipient.decodeUnsafe("caadf8ad86e61a10f4b4e14d51acf1483dac59c9e90cbca5eda8b840"),
            OutgoingTxId(1L)
          ),
          OutgoingTransaction(
            Token(123),
            OutgoingTxRecipient.decodeUnsafe("caadf8ad86e61a10f4b4e14d51acf1483dac59c9e90cbca5eda8b840"),
            OutgoingTxId(2L)
          )
        )
      )(
        validatorKey
      )
    assert(signature.signature.toBytes == expectedSignature)
    assert(signature.rootHash.value == expectedRootHash)
  }

  "should not fail if recipient address is an arbitrary byteArray value" in {
    forAll(testing.Generators.randomSizeByteStringGen(0, 128)) { bs =>
      MerkleRootSigner.sign(
        None,
        sidechainParams,
        NonEmptyList.one(
          OutgoingTransaction(
            Token(123),
            OutgoingTxRecipient(bs),
            OutgoingTxId(1L)
          )
        )
      )(
        validatorKey
      )
    }
  }
}
