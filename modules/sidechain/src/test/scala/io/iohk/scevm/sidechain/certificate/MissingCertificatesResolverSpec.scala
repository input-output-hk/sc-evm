package io.iohk.scevm.sidechain.certificate

import cats.syntax.all._
import cats.{Id, ~>}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.sidechain.BridgeContract.MerkleRootEntry.NewMerkleRoot
import io.iohk.scevm.sidechain.SidechainEpoch
import io.iohk.scevm.sidechain.SidechainFixtures.SidechainEpochDerivationStub
import io.iohk.scevm.sidechain.certificate.MissingCertificatesResolver.MainchainDataProvider
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.trustlesssidechain.cardano.{
  Blake2bHash32,
  CommitteeNft,
  MainchainBlockNumber,
  MainchainEpoch,
  MainchainSlot,
  MainchainTxHash,
  MerkleRootNft,
  UtxoId,
  UtxoInfo
}
import org.scalatest.TryValues
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Try

class MissingCertificatesResolverSpec extends AnyWordSpec with TryValues {
  val initialCommitteeNft = CommitteeNft(
    UtxoInfo(
      UtxoId.parseUnsafe("7247647315d327d4e56fd3fe62d45be1dc3a76a647c910e0048cca8b97c8df3e#42"),
      MainchainEpoch(1),
      MainchainBlockNumber(1L),
      MainchainSlot(55),
      0
    ),
    Blake2bHash32.fromHexUnsafe("149e9675f5c3bf7eb6ac7048ec15d21bcbf153b92c1bbbfc10ae385ef8b37c6e"),
    SidechainEpoch(1)
  )
  implicit val identityToTryTransformation: Id ~> Try = λ[Id ~> Try](a => Try(a))
  def merkleRootNft(hex: String): Option[MerkleRootNft] =
    Some(
      MerkleRootNft(
        Hex.decodeUnsafe(hex),
        MainchainTxHash.decodeUnsafe("7247647315d327d4e56fd3fe62d45be1dc3a76a647c910e0048cca8b97c8df3e"),
        MainchainBlockNumber(12L),
        None
      )
    )

  "should return empty list if we are in the first epoch" in {
    val resolver = new MissingCertificatesResolverImpl[Id, Try](
      new SidechainEpochDerivationStub[Id](currentEpoch = SidechainEpoch(1)),
      MainchainDataProviderStub(initialCommitteeNft, None),
      e => Try(Map.empty.get(e))
    )
    val result = resolver.getMissingCertificates(100).success.value
    assert(result == List.empty.asRight)
  }

  "should return empty list if there is a certificate for the previous epoch" in {
    val resolver = new MissingCertificatesResolverImpl[Id, Try](
      new SidechainEpochDerivationStub[Id](currentEpoch = SidechainEpoch(2)),
      MainchainDataProviderStub(initialCommitteeNft, None),
      e => Try(Map.empty.get(e))
    )
    val result = resolver.getMissingCertificates(100).success.value
    assert(result == List.empty.asRight)
  }

  "should return single missing certificate if there is no certificate for previous epoch (no transactions)" in {
    val resolver = new MissingCertificatesResolverImpl[Id, Try](
      new SidechainEpochDerivationStub[Id](currentEpoch = SidechainEpoch(3)),
      MainchainDataProviderStub(initialCommitteeNft, None),
      e => Try(Map.empty.get(e))
    )
    val result = resolver.getMissingCertificates(100).success.value
    assert(result == List(SidechainEpoch(2) -> List.empty[RootHash]).asRight)
  }

  "should return all missing certificates from previous epochs (no transactions)" in {
    val resolver = new MissingCertificatesResolverImpl[Id, Try](
      new SidechainEpochDerivationStub[Id](currentEpoch = SidechainEpoch(6)),
      MainchainDataProviderStub(initialCommitteeNft, None),
      e => Try(Map.empty.get(e))
    )
    val result = resolver.getMissingCertificates(100).success.value
    assert(
      result == List(
        SidechainEpoch(2) -> List.empty[RootHash],
        SidechainEpoch(3) -> List.empty[RootHash],
        SidechainEpoch(4) -> List.empty[RootHash],
        SidechainEpoch(5) -> List.empty[RootHash]
      ).asRight
    )
  }

  "should return single missing certificate with merkle root if there is no certificate for previous epoch" in {
    val resolver = new MissingCertificatesResolverImpl[Id, Try](
      new SidechainEpochDerivationStub[Id](currentEpoch = SidechainEpoch(3)),
      MainchainDataProviderStub(initialCommitteeNft, None),
      e => Try(Map(SidechainEpoch(2) -> NewMerkleRoot(RootHash.decodeUnsafe("aaeeff"), None)).get(e))
    )
    val result = resolver.getMissingCertificates(100).success.value
    assert(result == List(SidechainEpoch(2) -> List(RootHash.decodeUnsafe("aaeeff"))).asRight)
  }

  "should return all missing certificates from previous epochs together with their merkle roots" in {
    val resolver = new MissingCertificatesResolverImpl[Id, Try](
      new SidechainEpochDerivationStub[Id](currentEpoch = SidechainEpoch(6)),
      MainchainDataProviderStub(initialCommitteeNft, None),
      e =>
        Try(
          Map(
            SidechainEpoch(2) -> NewMerkleRoot(RootHash.decodeUnsafe("aaeeff2"), None),
            SidechainEpoch(4) -> NewMerkleRoot(RootHash.decodeUnsafe("aaeeff4"), None),
            SidechainEpoch(5) -> NewMerkleRoot(RootHash.decodeUnsafe("aaeeff5"), None)
          ).get(e)
        )
    )
    val result = resolver.getMissingCertificates(100).success.value
    assert(
      result == List(
        SidechainEpoch(2) -> List(RootHash.decodeUnsafe("aaeeff2")),
        SidechainEpoch(3) -> List.empty[RootHash],
        SidechainEpoch(4) -> List(RootHash.decodeUnsafe("aaeeff4")),
        SidechainEpoch(5) -> List(RootHash.decodeUnsafe("aaeeff5"))
      ).asRight
    )
  }

  "should return single missing certificate without merkle root if the merkle root was already uploaded" in {
    val resolver = new MissingCertificatesResolverImpl[Id, Try](
      new SidechainEpochDerivationStub[Id](currentEpoch = SidechainEpoch(3)),
      MainchainDataProviderStub(initialCommitteeNft, merkleRootNft("aaeeff2")),
      e => Try(Map(SidechainEpoch(2) -> NewMerkleRoot(RootHash.decodeUnsafe("aaeeff2"), None)).get(e))
    )
    val result = resolver.getMissingCertificates(100).success.value
    assert(result == List(SidechainEpoch(2) -> List.empty[RootHash]).asRight)
  }

  "should return missing certificates from previous epochs together with their merkle roots, when there exists some older merkle root inserted" in {
    val resolver = new MissingCertificatesResolverImpl[Id, Try](
      new SidechainEpochDerivationStub[Id](currentEpoch = SidechainEpoch(4)),
      MainchainDataProviderStub(initialCommitteeNft, merkleRootNft("111111")),
      e =>
        Try(
          Map(
            SidechainEpoch(2) -> NewMerkleRoot(RootHash.decodeUnsafe("222222"), Some(SidechainEpoch(1))),
            SidechainEpoch(3) -> NewMerkleRoot(RootHash.decodeUnsafe("333333"), Some(SidechainEpoch(2)))
          ).get(e)
        )
    )
    val result = resolver.getMissingCertificates(100).success.value
    assert(
      result == List(
        SidechainEpoch(2) -> List(RootHash.decodeUnsafe("222222")),
        SidechainEpoch(3) -> List(RootHash.decodeUnsafe("333333"))
      ).asRight
    )
  }

  "should return error when the committee nft was not initialized" in {
    val resolver = new MissingCertificatesResolverImpl[Id, Try](
      new SidechainEpochDerivationStub[Id](currentEpoch = SidechainEpoch(3)),
      MainchainDataProviderStub(None, None),
      e => Try(Map(SidechainEpoch(2) -> NewMerkleRoot(RootHash.decodeUnsafe("aaeeff"), None)).get(e))
    )
    assert(resolver.getMissingCertificates(100).success.value.isLeft)
  }

  "should limit returned missing certificates up to 100, starting from most oldest ones" in {
    val resolver = new MissingCertificatesResolverImpl[Id, Try](
      new SidechainEpochDerivationStub[Id](currentEpoch = SidechainEpoch(120)),
      MainchainDataProviderStub(initialCommitteeNft, None),
      e => Try(Map.empty.get(e))
    )
    val result = resolver.getMissingCertificates(100).success.value
    result match {
      case Left(err) => fail(err)
      case Right(epochs) =>
        assert(epochs.head == (SidechainEpoch(2), List.empty))
        assert(epochs.size == 100)
    }
  }

  case class MainchainDataProviderStub(
      committeeNft: Option[CommitteeNft],
      onChainMerkleRootHash: Option[MerkleRootNft]
  ) extends MainchainDataProvider[Id] {
    override def getLastOnChainCommitteeNft(): Id[Option[CommitteeNft]] = committeeNft

    override def getLastOnChainMerkleRootHash(): Id[Option[MerkleRootNft]] = onChainMerkleRootHash
  }
  object MainchainDataProviderStub {
    def apply(
        committeeNft: CommitteeNft,
        onChainMerkleRootHash: Option[MerkleRootNft]
    ): MainchainDataProviderStub = new MainchainDataProviderStub(committeeNft.some, onChainMerkleRootHash)
  }
}
