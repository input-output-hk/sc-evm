package io.iohk.consensus

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.domain._
import io.iohk.scevm.ledger.{LeaderElection, SlotDerivation}
import io.iohk.scevm.testing.BlockGenerators.obftBlockHeaderGen
import org.scalacheck.Gen

import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration

class ChainGenerator(
    leaderElection: LeaderElection[IO],
    slotDerivation: SlotDerivation,
    slotDuration: FiniteDuration,
    pubPrvKeyMapping: Map[ECDSA.PublicKey, ECDSA.PrivateKey]
) {

  private def obftHeaderChildGen(parent: ObftHeader): Gen[ObftHeader] = {

    val random         = 1000 + scala.util.Random.nextInt(10_000)
    val nextTimestamp  = parent.unixTimestamp.add(slotDuration * random)
    val nextSlotNumber = slotDerivation.getSlot(nextTimestamp).toOption.get // .get because must be after genesis
    val pubKey =
      leaderElection
        .getSlotLeader(nextSlotNumber)
        .unsafeRunSync()
        .toOption
        .get // .get because validators list must not be empty
    val prvKey = pubPrvKeyMapping(pubKey) // unsafe access because the mapping should be complete

    obftBlockHeaderGen
      .map { header =>
        ObftHeaderUnsigned(
          parentHash = parent.hash,
          number = parent.number.next,
          slotNumber = nextSlotNumber,
          beneficiary = Address.fromPublicKey(pubKey),
          stateRoot = parent.stateRoot,
          transactionsRoot = header.transactionsRoot,
          receiptsRoot = header.receiptsRoot,
          logsBloom = header.logsBloom,
          gasLimit = header.gasLimit,
          gasUsed = header.gasUsed,
          unixTimestamp = nextTimestamp,
          publicSigningKey = pubKey
        ).sign(prvKey)
      }
  }

  private def addOneChild(gen: Gen[List[ObftHeader]]): Gen[List[ObftHeader]] =
    gen.flatMap(list => obftHeaderChildGen(list.last).map(child => list :+ child))

  /** Extends the base chain
    * @param parent the chain to extend
    * @param number the number of new blocks
    * @return the base chain appended by the generated extension
    */
  private def validChainGen(parent: ObftHeader, number: Int): Gen[List[ObftHeader]] = {
    @tailrec
    def helper(gen: Gen[List[ObftHeader]], occ: Int): Gen[List[ObftHeader]] =
      if (occ == 0) gen
      else helper(addOneChild(gen), occ - 1)

    if (number < 1) Gen.lzy(List(parent))
    else helper(Gen.lzy(List(parent)), number)
  }

  /** Generate consistent blocks based on the parent
    * @param parent the ancestor of the chain
    * @param extensionLength the number of blocks to generate
    * @return the extension only
    */
  def genValidExtensionChain(parent: ObftBlock, extensionLength: Int): List[ObftBlock] =
    validChainGen(parent.header, extensionLength).sample.get
      .map(header => ObftBlock(header, ObftBody.empty))
      .tail

}
