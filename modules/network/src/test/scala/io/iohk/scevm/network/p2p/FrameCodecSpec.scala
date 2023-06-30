package io.iohk.scevm.network.p2p

import io.iohk.bytes.ByteString
import io.iohk.ethereum.rlp.RLPImplicitConversions._
import io.iohk.ethereum.rlp.RLPImplicits._
import io.iohk.ethereum.rlp.{RLPEncodeable, RLPList, rawDecode}
import io.iohk.scevm.network.rlpx.{Frame, FrameCodec, Header}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class FrameCodecSpec extends AnyFlatSpec with Matchers {

  import DummyMsg._

  it should "send message and receive a response" in new SecureChannelSetup {
    val frameCodec       = new FrameCodec(secrets)
    val remoteFrameCodec = new FrameCodec(remoteSecrets)

    val sampleMessage        = DummyMsg(2310, ByteString("Sample Message"))
    val sampleMessageEncoded = ByteString(sampleMessage.toBytes)
    val sampleMessageFrame = Frame(
      Header(sampleMessageEncoded.length, 0, None, Some(sampleMessageEncoded.length)),
      sampleMessage.code,
      sampleMessageEncoded
    )
    val sampleMessageData        = remoteFrameCodec.writeFrames(Seq(sampleMessageFrame))
    val sampleMessageReadFrames  = frameCodec.readFrames(sampleMessageData)
    val sampleMessageReadMessage = sampleMessageReadFrames.head.payload.toArray[Byte].toSample

    sampleMessageReadMessage shouldBe sampleMessage
  }

  case class DummyMsg(aField: Int, anotherField: ByteString) extends Message {
    val code: Int = DummyMsg.code

    def toRLPEncodable: RLPEncodeable = RLPList(aField, anotherField)
  }

  object DummyMsg {
    val code: Int = 2323

    implicit class DummyMsgDec(val bytes: Array[Byte]) {
      def toSample: DummyMsg = rawDecode(bytes) match {
        case RLPList(aField, anotherField) => DummyMsg(aField, anotherField)
        case _                             => throw new RuntimeException("Cannot decode Status")
      }
    }
  }
}
