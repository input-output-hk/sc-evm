package io.iohk.scevm.mpt

import io.iohk.bytes.ByteString
import io.iohk.scevm.mpt.HexPrefix.bytesToNibbles
import io.iohk.scevm.mpt.{BranchNode, ExtensionNode, HashNode, LeafNode, MptNode, MptTraversals}
import org.scalacheck.Gen

object Generators {

  import io.iohk.scevm.testing.Generators._

  def branchNodeGen: Gen[BranchNode] = for {
    children <- Gen
                  .listOfN(16, byteStringOfLengthNGen(32))
                  .map(childrenList => childrenList.map(child => HashNode(child.toArray[Byte])))
    terminator <- byteStringOfLengthNGen(32)
  } yield {
    val branchNode = BranchNode(children.toArray, Some(terminator))
    val asRlp      = MptTraversals.encode(branchNode)
    branchNode.copy(parsedRlp = Some(asRlp))
  }

  def extensionNodeGen: Gen[ExtensionNode] = for {
    keyNibbles <- byteArrayOfNItemsGen(32)
    value      <- byteStringOfLengthNGen(32)
  } yield {
    val extNode = ExtensionNode(ByteString(bytesToNibbles(keyNibbles)), HashNode(value.toArray[Byte]))
    val asRlp   = MptTraversals.encode(extNode)
    extNode.copy(parsedRlp = Some(asRlp))
  }

  def leafNodeGen: Gen[LeafNode] = for {
    keyNibbles <- byteArrayOfNItemsGen(32)
    value      <- byteStringOfLengthNGen(32)
  } yield {
    val leafNode = LeafNode(ByteString(bytesToNibbles(keyNibbles)), value)
    val asRlp    = MptTraversals.encode(leafNode)
    leafNode.copy(parsedRlp = Some(asRlp))
  }

  def nodeGen: Gen[MptNode] = Gen.choose(0, 2).flatMap { i =>
    i match {
      case 0 => branchNodeGen
      case 1 => extensionNodeGen
      case 2 => leafNodeGen
    }
  }

  def listOfNodes(min: Int, max: Int): Gen[Seq[MptNode]] = for {
    size  <- intGen(min, max)
    nodes <- Gen.listOfN(size, nodeGen)
  } yield nodes

}
