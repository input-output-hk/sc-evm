package io.iohk.scevm.network

import akka.actor.Actor
import cats.effect.kernel.{Async, Ref, Resource}
import cats.effect.{IO, Sync}
import cats.implicits._
import fs2.concurrent.{SignallingRef, Topic}
import io.iohk.scevm.network.PeerEvent._
import io.iohk.scevm.network.PeerEventBus.SubscriptionClassifier
import io.iohk.scevm.network.p2p.messages.OBFT1.ResponseMessage
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Helper trait to make the transition to remove PeerEventBusActor and bridge the gap in actors */
trait PeerEventBus[F[_]] {

  /** Create a subscription to the peer event bus for the given classifiers */
  def subscribe(classifiers: SubscriptionClassifier*): Resource[F, PeerEventBus.Subscription[F]]

  /** Push an event to the peer event bus */
  def publish(event: PeerEvent): F[Unit]
}

object PeerEventBus {

  final case class Subscription[F[_]](
      stream: fs2.Stream[F, PeerEvent],
      filter: Ref[F, Set[SubscriptionClassifier]]
  )

  def apply[F[_]: Async](topic: Topic[F, PeerEvent], bufferSize: Int): PeerEventBus[F] = {
    val log = Slf4jLogger.getLoggerFromClass[F](classOf[PeerEventBus[F]])
    new PeerEventBus[F] {
      override def subscribe(classifiers: SubscriptionClassifier*): Resource[F, PeerEventBus.Subscription[F]] =
        topic
          .subscribeAwait(bufferSize)
          .evalMap { stream =>
            for {
              filter <- SignallingRef[F, Set[SubscriptionClassifier]](classifiers.toSet)
            } yield Subscription(
              stream
                .zip(filter.continuous)
                .collect { case (event, filter) if filter.exists(_.classifies(event)) => event },
              filter
            )
          }

      override def publish(event: PeerEvent): F[Unit] = topic
        .publish1(event)
        .flatTap {
          case Left(Topic.Closed) =>
            log.warn(
              "Published an event on a closed peer event bus. It should not happen unless the application is closing."
            )
          case Right(()) => Sync[F].unit
        }
        .map(_ => ())
    }
  }

  sealed trait SubscriptionClassifier {
    def classifies: PeerEvent => Boolean
    def subtract: SubscriptionClassifier => SubscriptionClassifier
  }

  sealed trait PeerSelector {
    def contains(peerId: PeerId): Boolean
  }

  object PeerSelector {

    case object AllPeers extends PeerSelector {
      override def contains(p: PeerId): Boolean = true
    }

    final case class WithId(peerId: PeerId) extends PeerSelector {
      override def contains(p: PeerId): Boolean = p == peerId
    }

  }

  object SubscriptionClassifier {
    final case class MessageClassifier(messageCodes: Set[Int], peerSelector: PeerSelector)
        extends SubscriptionClassifier {
      val classifies: PeerEvent => Boolean = {
        case MessageFromPeer(peerId, message) => peerSelector.contains(peerId) && messageCodes(message.code)
        case _                                => false
      }

      val subtract: SubscriptionClassifier => SubscriptionClassifier = {
        case MessageClassifier(codes, `peerSelector`) => copy(messageCodes = messageCodes -- codes)
        case _                                        => this
      }
    }

    final case class ResponseClassifier(peerSelector: PeerSelector, requestId: RequestId)
        extends SubscriptionClassifier {
      val classifies: PeerEvent => Boolean = {
        case MessageFromPeer(peerId, message: ResponseMessage) =>
          peerSelector.contains(peerId) && message.requestId == requestId
        case _ => false
      }

      val subtract: SubscriptionClassifier => SubscriptionClassifier = _ => this
    }

    final case class PeerDisconnectedClassifier(peerSelector: PeerSelector) extends SubscriptionClassifier {
      val classifies: PeerEvent => Boolean = {
        case PeerDisconnected(peerId) => peerSelector.contains(peerId)
        case _                        => false
      }

      val subtract: SubscriptionClassifier => SubscriptionClassifier = _ => this
    }

    case object PeerHandshakedClassifier extends SubscriptionClassifier {
      val classifies: PeerEvent => Boolean = {
        case PeerHandshakeSuccessful(_, _) => true
        case _                             => false
      }

      val subtract: SubscriptionClassifier => SubscriptionClassifier = _ => this
    }
  }
}

trait ActorPeerEventBusSupport { this: Actor =>
  import cats.effect.unsafe.implicits.global

  protected val peerEventBus: PeerEventBus[IO]
  protected def initialFilter: Seq[SubscriptionClassifier]
  protected var subscription: PeerEventBus.Subscription[IO]     = _
  protected[ActorPeerEventBusSupport] var unsubscribe: IO[Unit] = _

  override def preStart(): Unit =
    subscribeToEventBus()
      .map { case (sub, unsub) =>
        subscription = sub
        unsubscribe = unsub
      }
      .unsafeRunSync()

  override def postStop(): Unit =
    unsubscribe.unsafeRunSync()

  private def subscribeToEventBus(): IO[(PeerEventBus.Subscription[IO], IO[Unit])] =
    for {
      (subscription, unsubscribe) <- peerEventBus.subscribe(initialFilter: _*).allocated
      _ <- subscription.stream
             .evalTap(event => IO.delay(self ! event))
             .compile
             .drain
             .start
    } yield (subscription, unsubscribe)

}
