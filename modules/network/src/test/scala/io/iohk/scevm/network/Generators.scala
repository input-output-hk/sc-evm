package io.iohk.scevm.network

import io.iohk.scevm.network.domain.{ChainTooShort, StableHeaderScore}
import io.iohk.scevm.testing.BlockGenerators.obftBlockHeaderGen
import io.iohk.scevm.testing.Generators.intGen
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen

import java.net.InetSocketAddress

object Generators {

  private val scoreGen = Gen.choose(0.0, 1.0)

  val peerIdGen: Gen[PeerId] =
    intGen.map(num => PeerId(s"akka://generated/peer/id/$num"))

  def peerGen: Gen[Peer] =
    for {
      peerId    <- peerIdGen
      ip        <- Gen.listOfN(4, Gen.choose(0, 255)).map(_.mkString("."))
      port      <- Gen.choose(10000, 60000)
      ageMillis <- Gen.choose(0, 24 * 60 * 60 * 1000)
      incoming  <- arbitrary[Boolean]
    } yield Peer(
      peerId,
      remoteAddress = new InetSocketAddress(ip, port),
      ref = null,
      incomingConnection = incoming,
      nodeId = None,
      createTimeMillis = System.currentTimeMillis - ageMillis
    )

  def peerInfoGen: Gen[PeerInfo] =
    for {
      stableHeaderOpt <- Gen.option(obftBlockHeaderGen)
      score           <- scoreGen
      tooShort        <- Gen.frequency((1, true), (9, false))

    } yield PeerInfo(
      stableHeaderOpt.map(stableHeader =>
        if (tooShort) {
          ChainTooShort(stableHeader)
        } else {
          StableHeaderScore(stableHeader, score)
        }
      )
    )

  def peerWithInfoGen: Gen[PeerWithInfo] =
    for {
      peer     <- peerGen
      peerInfo <- peerInfoGen
    } yield PeerWithInfo(peer, peerInfo)

  def requestIdGen: Gen[RequestId] =
    Gen.long.map(RequestId(_))
}
