package io.iohk.scevm.sidechain.transactions

import cats.effect.{IO, Resource}
import fs2.concurrent.Signal
import io.bullet.borer.Cbor
import io.iohk.bytes.ByteString
import io.iohk.scevm.consensus.pos.CurrentBranch
import io.iohk.scevm.domain
import io.iohk.scevm.exec.utils.TestInMemoryWorldState
import io.iohk.scevm.exec.vm.WorldType
import io.iohk.scevm.plutus.{Datum, DatumDecoder}
import io.iohk.scevm.sidechain.SidechainEpoch
import io.iohk.scevm.sidechain.transactions.MerkleProofProvider.{CombinedMerkleProof, CombinedMerkleProofWithDetails}
import io.iohk.scevm.sidechain.transactions.MerkleProofService.combinedMerkleProofDatumCodec
import io.iohk.scevm.testing.{BlockGenerators, IOSupport, NormalPatience}
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.wordspec.AnyWordSpec

class MerkleProofServiceSpec
    extends AnyWordSpec
    with ScalaFutures
    with IOSupport
    with NormalPatience
    with OptionValues {
  private val genesis = BlockGenerators.obftBlockHeaderGen.sample.get
  private val originalMp = CombinedMerkleProofWithDetails(
    merkletree.RootHash(ByteString(1, 2, 3)),
    CombinedMerkleProof(
      MerkleTreeEntry(
        OutgoingTxId(1L),
        domain.Token(5),
        OutgoingTxRecipient(ByteString(1)),
        Some(merkletree.RootHash(ByteString(7, 8, 9)))
      ),
      merkletree.MerkleProof(
        List(merkletree.Up(merkletree.Side.Right, merkletree.RootHash(ByteString(4, 5, 6))))
      )
    )
  )
  implicit val current: CurrentBranch.Signal[IO] = Signal.constant(CurrentBranch(genesis))
  val service: MerkleProofService[IO] = new MerkleProofService[IO](
    new MerkleProofProvider[IO] {
      override def getProof(
          world: WorldType
      )(epoch: SidechainEpoch, txId: OutgoingTxId): IO[Option[CombinedMerkleProofWithDetails]] =
        if (epoch == SidechainEpoch(1) && txId == OutgoingTxId(1)) {
          IO.pure(Some(originalMp))
        } else {
          IO.pure(Option.empty)
        }
    },
    (_, _) => Resource.pure(TestInMemoryWorldState.worldStateGen.sample.get)
  )
  "should return compacted merkle proof that can be deserialized to the original one" in {
    val serializedMerkleProof = service.getProof(SidechainEpoch(1), OutgoingTxId(1)).ioValue.value
    val decodedMp = DatumDecoder[CombinedMerkleProof].decode(
      Cbor.decode(serializedMerkleProof.merkleProof.bytes.toArray).to[Datum].value
    )
    decodedMp match {
      case Left(error)      => fail(error.message)
      case Right(compacted) => assert(compacted == originalMp.combined)
    }
  }

  "should return no merkle proof if there is no data for given (epoch, txId) pair" in {
    val compactedMerkleProof = service.getProof(SidechainEpoch(2), OutgoingTxId(1)).ioValue
    assert(compactedMerkleProof.isEmpty)
  }
}
