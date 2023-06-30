package io.iohk.scevm.testing

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Address, BlockContext, BlockHash, BlockNumber, ObftBlock, ObftBody, ObftHeader, Slot}
import io.iohk.scevm.testing.CryptoGenerators.{ecdsaPublicKeyGen, fakeSignatureGen}
import io.iohk.scevm.testing.Generators._
import io.iohk.scevm.testing.TransactionGenerators.signedTxGen
import org.scalacheck.Gen

import scala.annotation.tailrec
import scala.concurrent.duration.{DurationInt, DurationLong}

object BlockGenerators {

  lazy val blockHashGen: Gen[BlockHash] = byteStringOfLengthNGen(32).map(BlockHash(_))

  lazy val blockContextGen: Gen[BlockContext] = for {
    parentHash    <- blockHashGen
    number        <- blockNumberGen
    slot          <- anySlotGen
    gasLimit       = 8000000
    beneficiary   <- byteStringOfLengthNGen(20)
    unixTimestamp <- anyUnixTsGen
  } yield BlockContext(parentHash, number, slot, gasLimit, Address(beneficiary), unixTimestamp)

  lazy val obftBlockHeaderGen: Gen[ObftHeader] = for {
    parentHash       <- blockHashGen
    number           <- blockNumberGen
    slot             <- anySlotGen
    beneficiary      <- byteStringOfLengthNGen(20)
    stateRoot        <- byteStringOfLengthNGen(32)
    transactionsRoot  = emptyStorageRoot
    receiptsRoot      = emptyStorageRoot
    logsBloom         = ByteString(Array.fill(256)(0.toByte))
    gasLimit          = 8000000
    gasUsed           = 0
    unixTimestamp    <- anyUnixTsGen
    publicSigningKey <- ecdsaPublicKeyGen
    signature        <- fakeSignatureGen
  } yield ObftHeader(
    parentHash = parentHash,
    number = number,
    slotNumber = slot,
    beneficiary = Address(beneficiary),
    stateRoot = stateRoot,
    transactionsRoot = transactionsRoot,
    receiptsRoot = receiptsRoot,
    logsBloom = logsBloom,
    gasLimit = gasLimit,
    gasUsed = gasUsed,
    unixTimestamp = unixTimestamp,
    publicSigningKey = publicSigningKey,
    signature = signature
  )

  lazy val obftBlockBodyGen: Gen[ObftBody] =
    Gen.listOfN(10, signedTxGen(None)).map(ObftBody(_))

  lazy val obftEmptyBodyBlockGen: Gen[ObftBlock] =
    for {
      header <- obftBlockHeaderGen
    } yield ObftBlock(header, ObftBody.empty)

  lazy val obftBlockGen: Gen[ObftBlock] =
    for {
      header <- obftBlockHeaderGen
      body   <- obftBlockBodyGen
    } yield ObftBlock(header, body)

  lazy val obftSmallBlockGen: Gen[ObftBlock] =
    for {
      header <- obftBlockHeaderGen
      tx     <- TransactionGenerators.signedTxGen()
    } yield ObftBlock(header, ObftBody(Seq(tx)))

  /** Generate a list of blocks forming a chain based on the parentHash value (and the block.number)
    * @param minSize the inclusive minimal number of elements
    * @param maxSize the inclusive maximal number of elements
    * @return a chain of headers
    */
  def obftChainHeaderGen(minSize: Int, maxSize: Int): Gen[List[ObftHeader]] =
    for {
      startingBlockNumber <- Gen.choose(0L, 2000L)
      header              <- obftChainHeaderGen(minSize, maxSize, BlockNumber(startingBlockNumber))
    } yield header

  /** Generate a list of blocks forming a chain based on the parentHash value (and the block.number)
    * @param minSize the inclusive minimal number of elements
    * @param maxSize the inclusive maximal number of elements
    * @param startingBlockNumber the first block's number
    * @return a chain of headers
    */
  def obftChainHeaderGen(minSize: Int, maxSize: Int, startingBlockNumber: BlockNumber): Gen[List[ObftHeader]] =
    for {
      headers <- Gen.choose(minSize, maxSize).flatMap(size => Gen.listOfN(size, obftBlockHeaderGen))
    } yield headers
      .foldLeft(List.empty[ObftHeader]) { case (acc, block) =>
        val child = acc.headOption match {
          case Some(parent) =>
            block.copy(
              parentHash = parent.hash,
              number = parent.number.next,
              slotNumber = Slot(parent.slotNumber.number + 1),
              gasLimit = parent.gasLimit,
              unixTimestamp = parent.unixTimestamp.add(1.milli)
            )
          case None =>
            block.copy(number = startingBlockNumber) // first block of the list
        }
        child +: acc
      }
      .reverse

  def obftChainBlockWithGen(minSize: Int, maxSize: Int, blockGen: Gen[ObftBlock]): Gen[List[ObftBlock]] =
    for {
      blocks <- Gen.choose(minSize, maxSize).flatMap(size => Gen.listOfN(size, blockGen))
    } yield blocks
      .foldLeft(List.empty[ObftBlock]) { case (acc, block) =>
        val child = acc.headOption match {
          case Some(parent) =>
            block.copy(
              header = block.header.copy(
                parentHash = parent.hash,
                number = parent.number.next
              )
            )
          case None =>
            block // first block of the list
        }
        child +: acc
      }
      .reverse

  def obftChainBlockWithTransactionsGen(minSize: Int, maxSize: Int): Gen[List[ObftBlock]] =
    obftChainBlockWithGen(minSize, maxSize, obftBlockGen)

  def obftChainBlockWithoutTransactionsGen(minSize: Int, maxSize: Int): Gen[List[ObftBlock]] =
    obftChainBlockWithGen(minSize, maxSize, obftEmptyBodyBlockGen)

  def obftHeaderChildGen(parent: ObftHeader): Gen[ObftHeader] = for {
    header <- obftBlockHeaderGen
    slot   <- slotMaxAfterGen(parent.slotNumber)
  } yield header.copy(
    parentHash = parent.hash,
    number = parent.number.next,
    slotNumber = slot,
    unixTimestamp = parent.unixTimestamp.add(1.milli).add(scala.util.Random.nextLong(1000).millis)
  )

  def obftBlockChainGen(length: Int, genesis: ObftBlock, blockGen: Gen[ObftBlock]): Gen[List[ObftBlock]] =
    Gen.listOfN(length, Gen.zip(blockGen, Gen.choose(1, 10))).map { blocks =>
      blocks.scanLeft(genesis) { case (ObftBlock(parent, _), (ObftBlock(header, body), slots)) =>
        ObftBlock(
          header.copy(
            parentHash = parent.hash,
            number = parent.number.next,
            slotNumber = Slot(parent.slotNumber.number + slots),
            transactionsRoot = body.transactionsRoot,
            gasLimit = parent.gasLimit,
            unixTimestamp = parent.unixTimestamp.add(slots.millis)
          ),
          body
        )
      }
    }

  def obftExtendGen(gen: Gen[List[ObftHeader]]): Gen[List[ObftHeader]] =
    gen.flatMap(list => obftHeaderChildGen(list.last).map(child => list :+ child))

  def obftExtendChain(chain: List[ObftHeader], number: Int): Gen[List[ObftHeader]] = {
    @tailrec
    def helper(gen: Gen[List[ObftHeader]], occ: Int): Gen[List[ObftHeader]] =
      if (occ == 0) gen
      else helper(obftExtendGen(gen), occ - 1)

    if (number < 1) Gen.lzy(chain)
    else helper(Gen.lzy(chain), number)
  }

}
