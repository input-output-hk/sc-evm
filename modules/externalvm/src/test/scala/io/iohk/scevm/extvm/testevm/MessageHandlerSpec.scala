package io.iohk.scevm.extvm.testevm

import akka.actor.ActorSystem
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Keep, Sink, SinkQueueWithCancel, Source, SourceQueueWithComplete}
import akka.testkit.{TestKit, TestProbe}
import com.google.protobuf.CodedOutputStream
import io.iohk.bytes.ByteString
import io.iohk.extvm.kevm.{kevm_msg => msg}
import io.iohk.scevm.exec.vm.Generators
import io.iohk.scevm.extvm.Codecs.LengthPrefixSize
import io.iohk.scevm.extvm.Implicits._
import org.bouncycastle.util.BigIntegers
import org.scalamock.scalatest.MockFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scalapb.descriptors.{FieldDescriptor, PValue}
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

import java.math.BigInteger
import scala.concurrent.ExecutionContext.Implicits.global

class MessageHandlerSpec
    extends TestKit(ActorSystem("MessageHandlerSpec_System"))
    with AnyFlatSpecLike
    with Matchers
    with MockFactory
    with ScalaCheckPropertyChecks
    with BeforeAndAfterAll {

  import akka.pattern.pipe

  import scala.concurrent.duration._

  "MessageHandler" should "send arbitrary messages" in {

    val bytesGen = Generators.getByteStringGen(1, 10)

    forAll(bytesGen) { bytes =>
      val probe = TestProbe()

      val in         = mock[SinkQueueWithCancel[ByteString]]
      val (out, fut) = Source.queue[ByteString](1024, OverflowStrategy.dropTail).toMat(Sink.seq)(Keep.both).run()
      fut.pipeTo(probe.ref)

      /** Protobuf 0.10.10 has `toByteArray` as `final def`, so we can't use mock[GeneratedMessage]
        * Emulating the mock with a minimal RandomMessage constructed from generated `bytes`
        */
      final case class RandomMessage() extends GeneratedMessage {
        override def writeTo(output: CodedOutputStream): Unit = output.write(bytes.toArray, 0, bytes.length)
        override def getFieldByNumber(fieldNumber: Int): Any  = ???
        override def getField(field: FieldDescriptor): PValue = ???
        override def companion: GeneratedMessageCompanion[_]  = ???
        override def serializedSize: Int                      = bytes.length
        override def toProtoString: String                    = ???
      }
      val gm = RandomMessage()

      val messageHandler = new MessageHandler(in, out)
      messageHandler.sendMessage(gm)
      messageHandler.close()

      val lengthBytes = ByteString(BigIntegers.asUnsignedByteArray(LengthPrefixSize, BigInteger.valueOf(bytes.length)))
      probe.expectMsg(3.seconds, Seq(lengthBytes ++ bytes))
    }
  }

  it should "receive arbitrary code messages" in {
    val bytesGen = Generators.getByteStringGen(1, 8)

    forAll(bytesGen) { bytes =>
      val out     = mock[SourceQueueWithComplete[ByteString]]
      val codeMsg = msg.Code(byteStringToProtoByteString(bytes)).toByteArray
      val in      = Source.single(ByteString(codeMsg)).toMat(Sink.queue())(Keep.right).run()

      val messageHandler = new MessageHandler(in, out)
      val receivedMsg    = messageHandler.awaitMessage[msg.Code]
      (receivedMsg.code: ByteString) shouldBe bytes
      messageHandler.close()
    }
  }

  override def afterAll(): Unit =
    TestKit.shutdownActorSystem(system, verifySystemShutdown = true)

}
