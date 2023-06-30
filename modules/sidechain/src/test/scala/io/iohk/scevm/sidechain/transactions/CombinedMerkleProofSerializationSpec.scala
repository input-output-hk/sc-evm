package io.iohk.scevm.sidechain.transactions

import io.bullet.borer.Cbor
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.Token
import io.iohk.scevm.plutus.DatumEncoder.EncoderOpsFromDatum
import io.iohk.scevm.plutus.{Datum, DatumDecoder, DatumEncoder}
import io.iohk.scevm.sidechain.testing.SidechainGenerators
import io.iohk.scevm.sidechain.transactions.MerkleProofProvider.CombinedMerkleProof
import io.iohk.scevm.sidechain.transactions.MerkleProofService.combinedMerkleProofDatumCodec
import io.iohk.scevm.sidechain.transactions.merkletree.{MerkleProof, RootHash, Side, Up}
import io.iohk.scevm.testing.Generators
import org.scalacheck.Gen
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class CombinedMerkleProofSerializationSpec extends AnyWordSpec with ScalaCheckPropertyChecks {
  val sideGen: Gen[Side] = Gen.oneOf(merkletree.Side.Right, merkletree.Side.Left)
  val upGen: Gen[Up] = for {
    side <- sideGen
    hash <- Generators.byteStringOfLengthNGen(32).map(merkletree.RootHash.apply)
  } yield merkletree.Up(side, hash)

  private def merkleTreeElementGen(previousMrHash: Option[merkletree.RootHash]): Gen[MerkleTreeEntry] =
    SidechainGenerators.outgoingTransactionGen
      .map(ot => MerkleTreeEntry(ot.txIndex, ot.value, ot.recipient, previousMrHash))

  val combinedGen: Gen[CombinedMerkleProof] = for {
    merkleProof            <- Gen.listOf(upGen)
    previousMerkleRootHash <- Gen.option(Generators.byteStringOfLengthNGen(32).map(merkletree.RootHash.apply))
    element                <- merkleTreeElementGen(previousMerkleRootHash)
  } yield CombinedMerkleProof(element, MerkleProof(merkleProof))

  "should serialize and deserialize back to the same value" in {
    forAll(combinedGen) { combined =>
      val cbor         = DatumEncoder[CombinedMerkleProof].encode(combined).toCborBytes
      val decodedDatum = Cbor.decode(cbor.toArray).to[Datum]
      val decodedMp    = DatumDecoder[CombinedMerkleProof].decode(decodedDatum.value)
      assert(decodedMp == Right(combined))
    }
  }

  "should serialize given proof to the expected hex value" in {
    val proof = CombinedMerkleProof(
      transaction = MerkleTreeEntry(
        index = OutgoingTxId(12542),
        amount = Token(539422),
        recipient =
          OutgoingTxRecipient.decodeUnsafe("ecff7f9199faff168fb0015f01801b5e017f7fb2f3bdfc7fb58436d515000180"),
        previousOutgoingTransactionsBatch =
          Some(RootHash(Hex.decodeUnsafe("803399802c80ff3b7f82ff6f00d9887a51ff47ff7912ff15f10a84ff01ff7f01")))
      ),
      merkleProof = MerkleProof(
        List(
          Up(
            siblingSide = Side.Left,
            sibling = RootHash(Hex.decodeUnsafe("595a007f79ffff017f802effeb013f804935ff008054807f9a48e27f8c80004b"))
          ),
          Up(
            siblingSide = Side.Right,
            sibling = RootHash(Hex.decodeUnsafe("8073190a01517350690100944edbffffff01e54e130069ffeee4337f807fa0ff"))
          ),
          Up(
            siblingSide = Side.Left,
            sibling = RootHash(Hex.decodeUnsafe("00ffab800eff01ffc4ff8080ff77017b3d010100e60097010100ffd6ff3a0162"))
          ),
          Up(
            siblingSide = Side.Left,
            sibling = RootHash(Hex.decodeUnsafe("803d0ba3ff8080ff5cdf22dd00e38080807748fffd0078a59b80002964ff11c2"))
          ),
          Up(
            siblingSide = Side.Right,
            sibling = RootHash(Hex.decodeUnsafe("7b808fec00b2f580e101acb77f220180808035787380807f024d01d4b92ff301"))
          ),
          Up(
            siblingSide = Side.Right,
            sibling = RootHash(Hex.decodeUnsafe("a680e03c0001ea3e0016a9ac7f6c5be0017f66802b800180000001ff88e00079"))
          ),
          Up(
            siblingSide = Side.Left,
            sibling = RootHash(Hex.decodeUnsafe("a9920088807fa280997f26f1800180ff2f5ffe700032ff017f7f807280a0aa00"))
          )
        )
      )
    )

    val cbor = DatumEncoder[CombinedMerkleProof].encode(proof).toCborBytes
    // this test vector has been submitted to the trustless-sidechain repository as a reference value
    // if anything changes here it should be also updated there!!!
    // see https://github.com/mlabs-haskell/trustless-sidechain/issues/249 for details
    assert(
      cbor == Hex.decodeUnsafe(
        "d8799fd8799f1930fe1a00083b1e5820ecff7f9199faff168fb0015f01801b5e017f7fb2f3bdfc7fb58436d515000180d8799f5820803399802c80ff3b7f82ff6f00d9887a51ff47ff7912ff15f10a84ff01ff7f01ffff9fd8799f005820595a007f79ffff017f802effeb013f804935ff008054807f9a48e27f8c80004bffd8799f0158208073190a01517350690100944edbffffff01e54e130069ffeee4337f807fa0ffffd8799f00582000ffab800eff01ffc4ff8080ff77017b3d010100e60097010100ffd6ff3a0162ffd8799f005820803d0ba3ff8080ff5cdf22dd00e38080807748fffd0078a59b80002964ff11c2ffd8799f0158207b808fec00b2f580e101acb77f220180808035787380807f024d01d4b92ff301ffd8799f015820a680e03c0001ea3e0016a9ac7f6c5be0017f66802b800180000001ff88e00079ffd8799f005820a9920088807fa280997f26f1800180ff2f5ffe700032ff017f7f807280a0aa00ffffff"
      )
    )
  }
}
