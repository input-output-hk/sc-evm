package io.iohk.scevm.network

import akka.actor.ActorSystem
import akka.testkit.TestKit
import cats.effect.IO
import io.iohk.scevm.domain.{ObftBlock, ObftBody}
import io.iohk.scevm.network.{Generators => networkGenerators}
import io.iohk.scevm.testing.BlockGenerators.{obftBlockBodyGen, obftBlockGen}
import io.iohk.scevm.testing.NormalPatience
import org.scalacheck.{Arbitrary, Gen}
import org.scalamock.scalatest.MockFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfterAll, PrivateMethodTester}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class BlocksSpec
    extends TestKit(ActorSystem("BlocksSpec_System"))
    with AnyWordSpecLike
    with Matchers
    with PrivateMethodTester
    with MockFactory
    with ScalaFutures
    with ScalaCheckDrivenPropertyChecks
    with NormalPatience
    with BeforeAndAfterAll {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(sizeRange = 10)

  implicit val arbObftBlock: Arbitrary[ObftBlock] = Arbitrary {
    for {
      ObftBlock(header, body) <- obftBlockGen
    } yield ObftBlock(header.copy(transactionsRoot = body.transactionsRoot), body)
  }
  implicit val arbObftBody: Arbitrary[ObftBody] = Arbitrary(obftBlockBodyGen)
  implicit val arbPeerId: Arbitrary[PeerId]     = Arbitrary(networkGenerators.peerIdGen)
  implicit val arbPeer: Arbitrary[Peer]         = Arbitrary(networkGenerators.peerGen)

  implicit val taskLogger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  "Blocks.buildBlocks" should {
    "return matching headers and bodies only" in {
      forAll { (blocks: List[ObftBlock]) =>
        forAll(Gen.someOf(blocks.map(_.header)), Gen.someOf(blocks.map(_.body))) { (headers, bodies) =>
          val completeBlocks = blocks.filter(block => headers.contains(block.header) && bodies.contains(block.body))
          val result         = Blocks.buildBlocks(headers.toSeq, bodies.toSeq)
          result should contain theSameElementsAs completeBlocks
        }
      }
    }
  }

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system, verifySystemShutdown = true)
}
