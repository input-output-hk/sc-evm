package io.iohk.scevm.network.rlpx

import io.iohk.bytes.ByteString
import io.iohk.scevm.network.p2p
import io.iohk.scevm.network.p2p.MessageDecoder.{CodecError, DecodingError}
import io.iohk.scevm.network.p2p.messages.WireProtocol.Hello
import io.iohk.scevm.network.p2p.{Message, MessageDecoder}
import org.xerial.snappy.Snappy

import java.util.concurrent.atomic.AtomicInteger
import scala.util.{Failure, Success, Try}

class MessageCodec(frameCodec: FrameCodec, messageDecoder: MessageDecoder) {

  val MaxFramePayloadSize: Int = Int.MaxValue // no framing

  val contextIdCounter = new AtomicInteger

  // 16Mb in base 2
  val maxDecompressedLength = 16777216

  // MessageCodec is only used from actor context so it can be var
  @volatile
  private var remotePeerP2pVersion: Option[Long] = None

  private def setRemoteVersionBasedOnHelloMessage(m: Message): Unit =
    if (remotePeerP2pVersion.isEmpty) {
      m match {
        case hello: Hello =>
          remotePeerP2pVersion = Some(hello.p2pVersion)
        case _ =>
      }
    }

  def readMessages(data: ByteString): Seq[Either[DecodingError, Message]] = {
    val frames = frameCodec.readFrames(data)

    frames.map { frame =>
      val frameData = frame.payload.toArray
      val payloadTry =
        if (remotePeerP2pVersion.exists(version => version >= p2p.P2pVersion)) {
          decompressData(frameData)
        } else {
          Success(frameData)
        }

      payloadTry.toEither.left
        .map(CodecError.toDecodingError)
        .flatMap { payload =>
          val m = messageDecoder.fromBytes(frame.`type`, payload)
          m.foreach(setRemoteVersionBasedOnHelloMessage)
          m
        }
    }
  }

  private def decompressData(data: Array[Byte]): Try[Array[Byte]] =
    Try(Snappy.uncompressedLength(data)).flatMap { decompressedSize =>
      if (decompressedSize > maxDecompressedLength)
        Failure(new RuntimeException("Message size larger than 16mb"))
      else
        Try(Snappy.uncompress(data))
    }

  def encodeMessage(serializable: Message): ByteString = {
    val encoded: Array[Byte] = serializable.toBytes
    val numFrames            = Math.ceil(encoded.length / MaxFramePayloadSize.toDouble).toInt
    val contextId            = contextIdCounter.incrementAndGet()
    val frames = (0 until numFrames).map { frameNo =>
      val framedPayload = encoded.drop(frameNo * MaxFramePayloadSize).take(MaxFramePayloadSize)
      val payload =
        if (
          remotePeerP2pVersion
            .exists(version => version >= p2p.P2pVersion) && serializable.code != Hello.code
        ) {
          Snappy.compress(framedPayload)
        } else {
          framedPayload
        }

      val totalPacketSize = if (frameNo == 0) Some(encoded.length) else None
      val header =
        if (numFrames > 1) Header(payload.length, 0, Some(contextId), totalPacketSize)
        else Header(payload.length, 0, None, None)
      Frame(header, serializable.code, ByteString(payload))
    }

    frameCodec.writeFrames(frames)
  }

}
