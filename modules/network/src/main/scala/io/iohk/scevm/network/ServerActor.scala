package io.iohk.scevm.network

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.io.Tcp.{Bind, Bound, CommandFailed, Connected}
import akka.io.{IO, Tcp}
import cats.effect
import io.iohk.scevm.utils.{NodeStatusProvider, ServerStatus}

import java.net.InetSocketAddress

class ServerActor(nodeStatusProvider: NodeStatusProvider[effect.IO], peerManager: ActorRef)
    extends Actor
    with ActorLogging {

  import ServerActor._
  import context.system

  override def receive: Receive = { case StartServer(address) =>
    IO(Tcp) ! Bind(self, address)
    context.become(waitingForBindingResult)
  }

  def waitingForBindingResult: Receive = {
    case Bound(localAddress) =>
      import cats.effect.unsafe.implicits.global

      (for {
        nodeStatus <- nodeStatusProvider.getNodeStatus
        _ <- effect.IO.delay {
               log.info("P2P server listening on {}", localAddress)
               log.info(
                 "Node address: enode://{}@{}:{}",
                 nodeStatus.nodeId.toHex,
                 getHostName(localAddress.getAddress),
                 localAddress.getPort
               )
             }
        _ <- nodeStatusProvider.setServerStatus(ServerStatus.Listening(localAddress))
      } yield ()).unsafeRunSync()

      context.become(listening)

    case CommandFailed(b: Bind) =>
      log.warning("Binding to {} failed", b.localAddress)
      context.stop(self)
  }

  def listening: Receive = { case Connected(remoteAddress, _) =>
    val connection = sender()
    peerManager ! PeerManagerActor.HandlePeerConnection(connection, remoteAddress)
  }
}

object ServerActor {
  def props(nodeStatusProvider: NodeStatusProvider[effect.IO], peerManager: ActorRef): Props =
    Props(new ServerActor(nodeStatusProvider, peerManager))

  final case class StartServer(address: InetSocketAddress)
}
