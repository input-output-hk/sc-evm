package io.iohk.scevm.network.p2p

import cats.implicits._
import io.iohk.scevm.utils.Logger

@FunctionalInterface
trait MessageDecoder extends Logger { self =>
  import MessageDecoder._

  def fromBytes(`type`: Int, payload: Array[Byte]): Either[DecodingError, Message]

  def orElse(otherMessageDecoder: MessageDecoder): MessageDecoder = new MessageDecoder {
    override def fromBytes(`type`: Int, payload: Array[Byte]): Either[DecodingError, Message] =
      self.fromBytes(`type`, payload).leftFlatMap { _ =>
        otherMessageDecoder.fromBytes(`type`, payload)
      }
  }
}

object MessageDecoder {
  sealed trait DecodingError {
    def message: String
  }

  final case class UnknownNetworkMessage(messageCode: Int) extends DecodingError {
    val message: String = s"Unknown network message type: $messageCode"
  }

  final case class UnknownMessage(messageCode: Int) extends DecodingError {
    val message: String = s"Unknown message type: $messageCode"
  }

  final case class RLPDecodingError(message: String) extends DecodingError

  final case class CodecError(message: String) extends DecodingError

  object RLPDecodingError {
    def toDecodingError(failure: Throwable): DecodingError = RLPDecodingError(failure.getLocalizedMessage)
  }

  object CodecError {
    def toDecodingError(failure: Throwable): DecodingError = CodecError(failure.getLocalizedMessage)
  }
}
