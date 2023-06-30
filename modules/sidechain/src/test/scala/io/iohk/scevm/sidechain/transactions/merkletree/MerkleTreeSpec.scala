package io.iohk.scevm.sidechain.transactions.merkletree

import cats.data.NonEmptyList
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.showForByteString
import io.iohk.scevm.sidechain.transactions.merkletree.{MerkleProof, MerkleTree, RootHash, Side}
import org.scalacheck.Gen
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class MerkleTreeSpec extends AnyWordSpec with ScalaCheckPropertyChecks {

  private val nonEmptyByteString = Gen.stringOfN(10, Gen.asciiPrintableChar).map(str => ByteString(str))
  private val nonEmptyListOfByteStrings = for {
    a <- Gen.chooseNum(1, 100)
    b <- Gen.containerOfN[Set, ByteString](a, nonEmptyByteString)
  } yield NonEmptyList.fromListUnsafe(b.toList)

  private val nelOfByteStringsWithElement = for {
    list <- nonEmptyListOfByteStrings
    a    <- Gen.chooseNum(0, list.size - 1)
  } yield (list, Vector.from(list.toList)(a))

  private val nelOfByteStringsWithoutElement = for {
    list <- nonEmptyListOfByteStrings
    a    <- nonEmptyByteString.suchThat(a => !list.toList.contains(a))
  } yield (list, a)

  private val sideGen: Gen[Side] = Gen.oneOf(Side.Left, Side.Right)

  "merkle proof exists for every element that belongs to the merkle tree" in {
    forAll(nelOfByteStringsWithElement) { case (bytes, elem) =>
      val tree = MerkleTree.fromNonEmptyList(bytes)
      assert(MerkleTree.lookupMp(elem, tree).isDefined)
    }
  }

  "merkle proof does not exist for any element that doesn't not belong to merkle tree" in {
    forAll(nelOfByteStringsWithoutElement) { case (bytes, elem) =>
      val tree = MerkleTree.fromNonEmptyList(bytes)
      assert(MerkleTree.lookupMp(elem, tree).isEmpty)
    }
  }

  "merkle proof for every element from the tree is valid" in {
    forAll(nelOfByteStringsWithElement) { case (bytes, elem) =>
      val tree  = MerkleTree.fromNonEmptyList(bytes)
      val proof = MerkleTree.lookupMp(elem, tree)
      assert(MerkleTree.memberMp(elem, proof.get, tree.rootHash))
    }
  }

  "merkle proof with a modified path should not be valid" in {
    forAll(for {
      (xs, elem) <- nelOfByteStringsWithElement
      tree        = MerkleTree.fromNonEmptyList(xs)
      proof       = MerkleTree.lookupMp(elem, tree).get
      otherPath <-
        Gen.containerOfN[List, Side](proof.value.size, sideGen).suchThat(_ != proof.value.map(_.siblingSide))
      malformedProof = proof.value.zip(otherPath).map { case (old, next) => old.copy(siblingSide = next) }
    } yield (tree, MerkleProof(malformedProof), elem)) { case (tree, proof, elem) =>
      assert(!MerkleTree.memberMp(elem, proof, tree.rootHash))
    }
  }

  "merkle proof with modified hashes should not be valid" in {
    forAll(for {
      (xs, elem) <- nelOfByteStringsWithElement
      tree        = MerkleTree.fromNonEmptyList(xs)
      proof       = MerkleTree.lookupMp(elem, tree).get
      otherHashes <- Gen
                       .containerOfN[List, ByteString](proof.value.size, nonEmptyByteString)
                       .suchThat(_ != proof.value.map(_.sibling.value))
      malformedProof = proof.value.zip(otherHashes).map { case (old, next) => old.copy(sibling = RootHash(next)) }
    } yield (tree, MerkleProof(malformedProof), elem)) { case (tree, proof, elem) =>
      assert(!MerkleTree.memberMp(elem, proof, tree.rootHash))
    }
  }

  "merkle proof with a single modified hash should not be valid" in {
    forAll(for {
      (xs, elem)    <- nelOfByteStringsWithElement.suchThat(_._1.size > 1)
      tree           = MerkleTree.fromNonEmptyList(xs)
      proof          = MerkleTree.lookupMp(elem, tree).get
      otherHash     <- nonEmptyByteString.suchThat(bs => !proof.value.map(_.sibling.value).contains(bs))
      indexToChange <- Gen.chooseNum(0, proof.value.size - 1)
      malformedProof = MerkleProof(value = {
                         val (a, b) = proof.value.splitAt(indexToChange)
                         a ++ b match {
                           case ::(head, next) => head.copy(sibling = RootHash(otherHash)) :: next
                           case Nil            => Nil
                         }
                       })
    } yield (tree, malformedProof, elem)) { case (tree, proof, elem) =>
      assert(!MerkleTree.memberMp(elem, proof, tree.rootHash))
    }
  }

  "merkle proof with a single modified path should not be valid" in {
    forAll(for {
      (xs, elem)    <- nelOfByteStringsWithElement.suchThat(_._1.size > 1)
      tree           = MerkleTree.fromNonEmptyList(xs)
      proof          = MerkleTree.lookupMp(elem, tree).get
      indexToChange <- Gen.chooseNum(0, proof.value.size - 1)
      malformedProof = MerkleProof(value = {
                         val (a, b) = proof.value.splitAt(indexToChange)
                         a ++ b match {
                           case ::(head, next) =>
                             val oppositeSide = head.siblingSide match {
                               case Side.Left  => Side.Right
                               case Side.Right => Side.Left
                             }
                             head.copy(siblingSide = oppositeSide) :: next
                           case Nil => Nil
                         }
                       })
    } yield (tree, malformedProof, elem)) { case (tree, proof, elem) =>
      assert(!MerkleTree.memberMp(elem, proof, tree.rootHash))
    }
  }

  "should construct merkle tree from a big list" in {
    MerkleTree.fromNonEmptyList(
      NonEmptyList.fromListUnsafe(
        LazyList.iterate(0)(_ + 1).take(1_000_000).toList.map(i => ByteString(BigInt(i).toByteArray))
      )
    ) // no assertion here, the test passes if the construction doesn't blow up
  }

  "show should use hex notation" in {
    val s = show"${RootHash(ByteString("7" * 32))}"
    assert(s == "3737373737373737373737373737373737373737373737373737373737373737")
  }

  /** This reference root hash was obtained using cli from trustless-sidechain repository available here:
    * https://github.com/mlabs-haskell/trustless-sidechain/pull/157
    *
    *  > cabal run trustless-sidechain-merkle-tree -- rootHashFromList --input=test.bin --output=hash.bin
    *
    *  To create input file use source code and pass test data ["pomeranian", "maltese", "yorkie"] into
    *  `Hex.toHexString(Cbor.encode(DatumEncoder[List[ByteString]].encode(testData)).toByteArray)`
    *
    *  Put that hex into a file as a regular text and then use xxd to convert it to its binary equivalent:
    *  > xxd -r -p test.txt test.bin
    *
    *  Use xxd to read the hex from the output file:
    *  > xxd hash.bin
    *
    *  At the moment of writing the resulting hash contains additional characters due to redundant wrapping with
    *  ByteStringDatum and ConstructorDatum. Below test uses striped output that does not contain unnecessary bytes.
    *
    *  If you would like to obtain the same hash you might wrap the result as follows:
    *  `Hex.toHexString(Cbor.encode(ConstructorDatum(0, Seq(DatumEncoder[ByteString].encode(scevmRootHash)))).toByteArray)`
    */
  val ReferenceTestVectors: List[ReferenceTestVector] = List(
    ReferenceTestVector(
      testData = List("pomeranian", "maltese", "yorkie", "pug"),
      expectedHash = "3252615346b250f6c5e527ff71562240f1a8516db086fcfbb53bbcd038b0e4ba"
    ),
    ReferenceTestVector(
      testData = List("pomeranian", "maltese", "yorkie"),
      expectedHash = "66b753c022972c5b20f53823d2b65041d77f5d4d1573ba728c74407d0f756326"
    ),
    ReferenceTestVector(
      testData = List("pomeranian", "maltese"),
      expectedHash = "e582214fa3bc1b026427644cd8f1972fe3b486ced57be3324eb835ec9a76dccc"
    ),
    ReferenceTestVector(
      testData = List("pomeranian"),
      expectedHash = "a8ae306d7dddba230de2a8844d36d4ed5efcf9f4d32b01dd96f8e800a564ef31"
    )
  )
  ReferenceTestVectors.foreach { case ReferenceTestVector(testData, expectedHash) =>
    s"should result in the same root tree hash as haskell implementation for data ($testData)" in {
      val referenceRootHash = Hex.decodeUnsafe(expectedHash)
      val testDataAsBytes   = testData.map(ByteString.apply)
      val tree              = MerkleTree.fromNonEmptyList(NonEmptyList.fromListUnsafe(testDataAsBytes))
      assert(tree.rootHash.value == referenceRootHash, tree.rootHash.value.show)
    }
  }

}
final case class ReferenceTestVector(testData: List[String], expectedHash: String)
