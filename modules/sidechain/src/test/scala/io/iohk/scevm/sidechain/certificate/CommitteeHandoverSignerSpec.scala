package io.iohk.scevm.sidechain.certificate

import cats.Id
import cats.data.NonEmptyList
import com.softwaremill.diffx.scalatest.DiffShouldMatcher
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.sidechain.testing.SidechainGenerators
import io.iohk.scevm.sidechain.testing.SidechainGenerators.sidechainParamsGen
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sidechain.{SidechainEpoch, SidechainFixtures}
import io.iohk.scevm.testing.CoreDiffxInstances._
import io.iohk.scevm.testing.{CryptoGenerators, Generators}
import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

class CommitteeHandoverSignerSpec
    extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks
    with DiffShouldMatcher {
  private val nextCommitteeKeys = NonEmptyList.of(
    ECDSA.PublicKey.fromHexUnsafe(
      "f16df0d21e2a447d820999c6b65794820cd1920cafccc3a7b83956f6148441ed30853febc6321b1f0874ef4072c14b83e5fa3117bdaaff436a5fac2416fcc793"
    ),
    ECDSA.PublicKey.fromHexUnsafe(
      "8b098f1a3ccb005419df63dc1ce954a3f9071e2f41aefece0f86ee991285b498f551e4f85e3355550b809e048ceea3e162d2c9efbefbd2a469dee91e8d798121"
    ),
    ECDSA.PublicKey.fromHexUnsafe(
      "9ef0f8e7f2144461246f4d14772a34945069e0b746fc93d641f5cceb23dba760843238db4842c3e75ce2963a2c4969dd76f68e5a1e7a30c7dd8fdbc0745fff18"
    ),
    ECDSA.PublicKey.fromHexUnsafe(
      "74df74bd17e647fbea5ad07699ee36eae9247b2fb633f31223da66626e0832727785a49a0a425e19514363350eddcc4ecb04c89534e8acd0359380bea4d07e15"
    )
  )
  //nextCommitteeKeys have to have different order when sorted by bytes and by compressedBytes
  assert(nextCommitteeKeys.sortBy(k => k.bytes.toList) != nextCommitteeKeys.sortBy(k => k.compressedBytes.toList))

  private val sidechainParams = SidechainFixtures.sidechainParams
  private val validatorKey =
    ECDSA.PrivateKey.fromHexUnsafe("c0fff366fb01ff80008a005701ff5d804527190021e90051c200ff802f9e467f")
  private val expectedSignatureNoPrevMerkleRoot = Hex.decodeUnsafe(
    "92dca92b9abe64cb1ca8520c04b0b75edca9927b6a877b282c216c30c8b0558867ac3fee2b1e9599b79592193638506e4149f9065ad6ccaac4c33210dbd50bd11c"
  )
  private val expectedSignatureWithMerkleRoot = Hex.decodeUnsafe(
    "1a62f35da7d9d8ebecb4e8a8d9ab4ae51d846e698b5341edf0ea552bca8f6c8a3867db7d16253d7b00b3c9b74f5af336ed89d990f890c851fb8c0fac58601d7d1b"
  )
  private val merkleRoot = RootHash(Hex.decodeUnsafe("ab" * 32))
  // format: off
  private val tests = Table(
    ( "chain-params" , "committee"              , "prevMerkleRoot", "expectedSig"                     ),
    ( sidechainParams, nextCommitteeKeys        , None            , expectedSignatureNoPrevMerkleRoot ),
    ( sidechainParams, nextCommitteeKeys        , Some(merkleRoot), expectedSignatureWithMerkleRoot   ),
    // order should not matter
    ( sidechainParams, nextCommitteeKeys.reverse, None            , expectedSignatureNoPrevMerkleRoot ),
    ( sidechainParams, nextCommitteeKeys.reverse, Some(merkleRoot), expectedSignatureWithMerkleRoot   )
  )
  // format: on

  "should give expected result - snapshot test" in {
    // https://github.com/mlabs-haskell/trustless-sidechain/pull/287 adds matching test vectors
    forAll(tests) { case (chainParams, nextCommittee, merkleRoot, expected) =>
      val sig = new CommitteeHandoverSigner[Id].sign(
        chainParams,
        nextCommittee,
        merkleRoot,
        SidechainEpoch(123)
      )(validatorKey)
      sig.toBytes shouldMatchTo expected
    }
  }

  "should change whenever sidechain params change" in {
    forAll(sidechainParamsGen) { newParams =>
      val sig = new CommitteeHandoverSigner[Id].sign(
        newParams,
        nextCommitteeKeys.reverse,
        None,
        SidechainEpoch(123)
      )(validatorKey)

      assert(sig.toBytes != expectedSignatureNoPrevMerkleRoot)
    }
  }

  "should change whenever committee changes" in {
    forAll(for {
      committeeSize  <- Gen.chooseNum(1, 20)
      committee      <- Generators.genSetOfN[ECDSA.PublicKey](committeeSize, CryptoGenerators.ecdsaPublicKeyGen)
      prevMerkleRoot <- Gen.option(SidechainGenerators.rootHashGen)
    } yield (committee.toList, prevMerkleRoot)) { case (newCommittee, prevMerkleRoot) =>
      val sig = new CommitteeHandoverSigner[Id].sign(
        sidechainParams,
        NonEmptyList.fromListUnsafe(newCommittee),
        prevMerkleRoot,
        SidechainEpoch(123)
      )(validatorKey)

      assert(sig.toBytes != expectedSignatureNoPrevMerkleRoot)
    }
  }

  "should change whenever sidechainEpoch changes" in {
    forAll(Gen.posNum[Long].map(SidechainEpoch.apply)) { newEpoch =>
      val sig = new CommitteeHandoverSigner[Id].sign(
        sidechainParams,
        nextCommitteeKeys,
        None,
        newEpoch
      )(validatorKey)

      assert(sig.toBytes != expectedSignatureNoPrevMerkleRoot)
    }
  }

  implicit val loggerFactorId: LoggerFactory[Id] = NoOpFactory[Id]
}
