package io.iohk.scevm.extvm

import cats.MonadError
import cats.effect._
import cats.effect.std.Queue
import cats.syntax.all._
import com.comcast.ip4s.SocketAddress
import fs2.interop.scodec.StreamDecoder
import fs2.io.net.{Network, Socket, SocketOption}
import fs2.{Chunk, Stream}
import mouse.all.anySyntaxMouse
import org.typelevel.log4cats.Logger
import scodec.Encoder

import java.net.InetSocketAddress
import scala.concurrent.duration.{DurationInt, FiniteDuration}

/** A trait representing a TCP socket that receives messages of type `In` and sends messages of type `Out` */
trait MessageSocket[F[_], In, Out] {

  def send(out: Out): F[Unit]

  def receive: F[In]

}

object MessageSocket {

  /** @param inetSocketAddress The IP socket address of the socket endpoint
    * @param reuseAddress      Whether address may be reused (see `java.net.StandardSocketOptions.SO_REUSEADDR`)
    * @param sendBufferSize    Size of send buffer  (see `java.net.StandardSocketOptions.SO_SNDBUF`)
    * @param receiveBufferSize Size of receive buffer  (see `java.net.StandardSocketOptions.SO_RCVBUF`)
    * @param keepAlive         Whether keep-alive on TCP is used (see `java.net.StandardSocketOptions.SO_KEEPALIVE`)
    * @param noDelay           Whether tcp no-delay flag is set (see `java.net.StandardSocketOptions.TCP_NODELAY`)
    * @param connectRetryCount The number of times to retry connecting if it fails
    * @param connectRetryDelay The amount of time to wait between connection retries
    */
  final case class MessageSocketConfig(
      inetSocketAddress: InetSocketAddress,
      reuseAddress: Boolean = true,
      sendBufferSize: Int = 256 * 1024,
      receiveBufferSize: Int = 256 * 1024,
      keepAlive: Boolean = false,
      noDelay: Boolean = false,
      connectRetryCount: Int = 0,
      connectRetryDelay: FiniteDuration = 0.seconds
  )

  /** Connects to the specified IP address via TCP socket. The resource finalizer closes the socket. We require a `StreamDecoder`
    * for `In` and not `Out` because `receive` uses `Socket[F]` to produce a `Stream[F, Byte]` beneath. A `StreamDecoder` simplifies
    * transforming `Stream[F, Byte]` into `Stream[F, In]`. No such simplification is needed for encoding.
    *
    * @tparam In  The type of messages received
    * @tparam Out The type of messages sent
    * @throws java.nio.channels.UnresolvedAddressException      If the given remote address is not fully resolved
    * @throws java.nio.channels.UnsupportedAddressTypeException If the type of the given remote address is not supported
    * @throws SecurityException                                 If a security manager has been installed and it does not permit access to the given
    *                                                           remote endpoint
    * @throws java.io.IOException                               If an IO exception occurs
    */
  // scalastyle:off
  def connect[F[_]: Concurrent: Async, In, Out](
      messageSocketConfig: MessageSocketConfig,
      streamDecoder: StreamDecoder[In],
      encoder: Encoder[Out],
      logger: Logger[F]
  ): Resource[F, MessageSocket[F, In, Out]] = {

    val MessageSocketConfig(
      inetSocketAddress,
      reuseAddress,
      sendBufferSize,
      receiveBufferSize,
      keepAlive,
      noDelay,
      connectRetryCount,
      connectRetryDelay
    ) = messageSocketConfig

    val messageSocketResource =
      for {
        socketGroup <- Network[F].socketGroup()
        socket <- socketGroup.client(
                    SocketAddress.fromInetSocketAddress(inetSocketAddress),
                    List(
                      SocketOption.reuseAddress(reuseAddress),
                      SocketOption.sendBufferSize(sendBufferSize),
                      SocketOption.receiveBufferSize(receiveBufferSize),
                      SocketOption.keepAlive(keepAlive),
                      SocketOption.noDelay(noDelay)
                    )
                  )
        messageSocket <- constructSocket[F, In, Out](
                           socket,
                           streamDecoder,
                           encoder,
                           receiveBufferSize
                         )
      } yield messageSocket

    messageSocketResource.handleErrorWith[MessageSocket[F, In, Out], Throwable] { throwable =>
      if (connectRetryCount == 0) throwable.raiseError[Resource[F, *], MessageSocket[F, In, Out]]
      else {
        Resource.eval(logger.error(s"Failed to connect to $inetSocketAddress, retrying in $connectRetryDelay")) >>
          Temporal[Resource[F, *]].sleep(connectRetryDelay) >>
          connect[F, In, Out](
            messageSocketConfig.copy(connectRetryCount = connectRetryCount - 1),
            streamDecoder,
            encoder,
            logger
          )
      }
    }
  }
  // scalastyle:on

  /** Any errors in encoding and decoding are sequenced into `F` and bubbled up */
  private def constructSocket[F[_]: MonadError[*[_], Throwable]: Concurrent, In, Out](
      socket: Socket[F],
      streamDecoder: StreamDecoder[In],
      encoder: Encoder[Out],
      receiveBufferSize: Int
  ): Resource[F, MessageSocket[F, In, Out]] =
    for {
      readQueue <- Resource.eval(Queue.unbounded[F, In])
      deferred  <- Resource.eval(Deferred[F, Throwable])
      socket <- Concurrent[F]
                  .background {
                    Stream
                      .repeatEval(socket.read(receiveBufferSize))
                      .unNoneTerminate
                      .map(_.toBitVector)
                      .through(streamDecoder.toPipe)
                      .attemptTap(_.fold(deferred.complete, in => readQueue.offer(in).void) |> Stream.eval)
                      .compile
                      .drain
                  }
                  .as {
                    new MessageSocket[F, In, Out] {

                      /** @throws java.nio.channels.InterruptedByTimeoutException If `writeTimeout` elapses before any bytes are written
                        */
                      override def send(out: Out): F[Unit] =
                        MonadError[F, Throwable]
                          .fromTry(encoder.encode(out).toTry)
                          .flatMap(bitVector => socket.write(Chunk(bitVector.toByteArray: _*)))

                      /** @throws java.nio.channels.InterruptedByTimeoutException If `readTimeout` elapses before any bytes are read
                        */
                      override def receive: F[In] = deferred.tryGet >>= {
                        case Some(throwable) => throwable.raiseError[F, In]
                        case None            => readQueue.take
                      }
                    }
                  }
    } yield socket
}
