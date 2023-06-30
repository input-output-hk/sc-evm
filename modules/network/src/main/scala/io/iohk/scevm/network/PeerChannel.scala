package io.iohk.scevm.network

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._
import cats.{Applicative, Show}
import fs2.concurrent.Topic
import io.iohk.scevm.network.PeerChannel.{MultiplePeersAskFailure, SinglePeerAskFailure}
import io.iohk.scevm.network.p2p.messages.OBFT1.{FullBlocks, RequestMessage, ResponseMessage}

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

trait PeerChannel[F[_]] {
  def askPeers[Response <: ResponseMessage: ClassTag](
      peerIds: NonEmptyList[PeerId],
      requestMessage: RequestMessage
  ): F[Either[MultiplePeersAskFailure, Response]]
}

final case class TopicBasedPeerChannel[F[_]: Async](
    peerEventsTopic: Topic[F, PeerEvent],
    peerActionsTopic: Topic[F, PeerAction],
    requestTimeout: FiniteDuration,
    subscriptionQueueSize: Int
) extends PeerChannel[F] {

  /** Ask the provided peers sequentially (not broadcasting) until getting a response. If the first peer in the list
    * times out, the second one will be asked.
    */
  override def askPeers[Response <: ResponseMessage: ClassTag](
      peerIds: NonEmptyList[PeerId],
      requestMessage: RequestMessage
  ): F[Either[MultiplePeersAskFailure, Response]] =
    askPeer(peerIds.head, requestMessage).flatMap {
      case Left(SinglePeerAskFailure.Timeout) =>
        peerIds.tail match {
          case peerId :: tail => askPeers(NonEmptyList(peerId, tail), requestMessage)
          case _              => Applicative[F].pure(Left(MultiplePeersAskFailure.AllPeersTimedOut))
        }
      case Right(response) =>
        response match {
          case fullBlocksResponse: FullBlocks if fullBlocksResponse.blocks.isEmpty =>
            peerIds.tail match {
              case peerId :: tail => askPeers(NonEmptyList(peerId, tail), requestMessage)
              case _              => Applicative[F].pure(Right(response))
            }
          case _ => Applicative[F].pure(Right(response))
        }
    }

  /** Send `requestMessage` to a single peer, return a response with a matching `requestId` */
  private def askPeer[Response <: ResponseMessage: ClassTag](
      peerId: PeerId,
      requestMessage: RequestMessage
  ): F[Either[SinglePeerAskFailure, Response]] =
    peerEventsTopic
      .subscribeAwait(subscriptionQueueSize)
      .use { peerEventsStream =>
        for {
          _ <- peerActionsTopic.publish1(PeerAction.MessageToPeer(peerId, requestMessage))
          response <- peerEventsStream
                        .collectFirst {
                          case PeerEvent.MessageFromPeer(responsePeer, response: Response)
                              if response.requestId == requestMessage.requestId && responsePeer == peerId =>
                            response
                        }
                        .timeout(requestTimeout)
                        .compile
                        .last
                        .recover { case _: TimeoutException =>
                          None
                        }
        } yield response.toRight(SinglePeerAskFailure.Timeout)
      }
}

object PeerChannel {
  sealed trait SinglePeerAskFailure
  object SinglePeerAskFailure {
    case object Timeout extends SinglePeerAskFailure
  }

  sealed trait MultiplePeersAskFailure
  object MultiplePeersAskFailure {
    case object AllPeersTimedOut extends MultiplePeersAskFailure

    def userFriendlyMessage(error: MultiplePeersAskFailure): String = error match {
      case AllPeersTimedOut => "All peers timed out."
    }

    implicit val show: Show[MultiplePeersAskFailure] = cats.derived.semiauto.show
  }
}
