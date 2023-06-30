package io.iohk.scevm.extvm.testevm

import akka.stream.scaladsl.{SinkQueueWithCancel, SourceQueueWithComplete}
import io.iohk.bytes.ByteString
import io.iohk.scevm.extvm.Codecs.LengthPrefixSize
import org.bouncycastle.util.BigIntegers
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

import java.math.BigInteger
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

class MessageHandler(in: SinkQueueWithCancel[ByteString], out: SourceQueueWithComplete[ByteString])(implicit
    ec: ExecutionContext
) {

  private val AwaitTimeout = 5.minutes

  def sendMessage[M <: GeneratedMessage](msg: M): Unit = {
    val bytes       = msg.toByteArray
    val lengthBytes = ByteString(BigIntegers.asUnsignedByteArray(LengthPrefixSize, BigInteger.valueOf(bytes.length)))

    out.offer(lengthBytes ++ ByteString(bytes))
  }

  def awaitMessage[M <: GeneratedMessage](implicit companion: GeneratedMessageCompanion[M]): M = {
    val resF: Future[M] = in.pull().map {
      case Some(bytes) => companion.parseFrom(bytes.toArray)
      case None        => throw new RuntimeException("Stream completed")
    }

    Await.result(resF, AwaitTimeout)
  }

  def close(): Unit = {
    Try(in.cancel())
    Try(out.complete())
  }

}
