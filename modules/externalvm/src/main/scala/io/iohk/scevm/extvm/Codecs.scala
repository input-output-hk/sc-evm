package io.iohk.scevm.extvm

import fs2.interop.scodec.StreamDecoder
import org.bouncycastle.util.BigIntegers
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}
import scodec.bits.BitVector
import scodec.codecs._
import scodec.{Attempt, Encoder, SizeBound}

import java.math.BigInteger
import scala.util.{Failure, Success, Try}

object Codecs {

  val LengthPrefixSize: Int = 4

  def generatedMessageDecoder[A <: GeneratedMessage](c: GeneratedMessageCompanion[A]): StreamDecoder[A] =
    for {
      lengthDelimiterBytes <- StreamDecoder.many(bytes(LengthPrefixSize))
      lengthDelimiter       = lengthDelimiterBytes.toInt(signed = false)
      messageBytes         <- StreamDecoder.once(bytes(lengthDelimiter))
      message <- Try(c.parseFrom(messageBytes.toArray)) match {
                   case Failure(exception) => StreamDecoder.raiseError(exception)
                   case Success(value)     => StreamDecoder.emit(value)
                 }
    } yield message

  val generatedMessageEncoder: Encoder[GeneratedMessage] = new Encoder[GeneratedMessage] {
    override def encode(value: GeneratedMessage): Attempt[BitVector] = {
      val bytes = value.toByteArray
      Attempt.successful(
        BitVector(BigIntegers.asUnsignedByteArray(LengthPrefixSize, BigInteger.valueOf(bytes.length)) ++ bytes)
      )
    }
    override def sizeBound: SizeBound = SizeBound.unknown
  }
}
