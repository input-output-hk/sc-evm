package io.iohk.scevm.network.p2p.messages

import io.iohk.ethereum.rlp.RLPImplicitConversions._
import io.iohk.ethereum.rlp.RLPImplicits._
import io.iohk.ethereum.rlp._
import io.iohk.scevm.domain.NodeId
import io.iohk.scevm.network.p2p.Capability.Capabilities
import io.iohk.scevm.network.p2p.{Capability, Message, P2pVersion}
import io.iohk.scevm.utils.{NodeStatus, ServerStatus}

object WireProtocol {
  final case class Hello(
      p2pVersion: Long,
      clientId: String,
      capabilities: Seq[Capability],
      listenPort: Long,
      nodeId: NodeId
  ) extends Message {
    val code: Int = Hello.code
    override def toString: String =
      "Hello { " +
        s"p2pVersion: $p2pVersion " +
        s"clientId: $clientId " +
        s"capabilities: $capabilities " +
        s"listenPort: $listenPort " +
        s"nodeId: ${nodeId.toHex} " +
        "}"

    def toRLPEncodable: RLPEncodeable =
      RLPList(p2pVersion, clientId, RLPList(capabilities.map(_.toRLPEncodable): _*), listenPort, nodeId.byteString)
  }

  object Hello {
    val code = 0x00

    def fromNodeStatus(nodeStatus: NodeStatus, clientId: String): Hello = {
      val listenPort = nodeStatus.serverStatus match {
        case ServerStatus.Listening(address) => address.getPort
        case ServerStatus.NotListening       => 0
      }
      val capabilities = Capabilities.All

      Hello(
        p2pVersion = P2pVersion,
        clientId = clientId,
        capabilities = capabilities,
        listenPort = listenPort,
        nodeId = nodeStatus.nodeId
      )
    }

    object HelloSerializers {
      implicit class HelloDec(val bytes: Array[Byte]) extends AnyVal {
        import Capability._

        def toHello: Hello = rawDecode(bytes) match {
          case RLPList(p2pVersion, clientId, capabilities: RLPList, listenPort, nodeId, _*) =>
            Hello(p2pVersion, clientId, capabilities.items.flatMap(_.toCapability), listenPort, NodeId(nodeId))
          case undecoded => throw RLPException("Cannot decode Hello", undecoded)
        }
      }
    }

  }

  final case class Disconnect(reason: Long) extends Message {
    val code: Int                 = Disconnect.code
    override def toString: String = s"Disconnect(${Disconnect.reasonToString(reason)})"

    def toRLPEncodable: RLPEncodeable = RLPList(reason)
  }

  object Disconnect {
    val code = 0x01

    object Reasons {
      val DisconnectRequested            = 0x00
      val TcpSubsystemError              = 0x01
      val UselessPeer                    = 0x03
      val TooManyPeers                   = 0x04
      val AlreadyConnected               = 0x05
      val IncompatibleP2pProtocolVersion = 0x06
      val NullNodeIdentityReceived       = 0x07
      val ClientQuitting                 = 0x08
      val UnexpectedIdentity             = 0x09
      val IdentityTheSame                = 0xa
      val TimeoutOnReceivingAMessage     = 0x0b
      val Other                          = 0x10
    }

    def reasonToString(reasonCode: Long): String =
      reasonCode match {
        case Reasons.DisconnectRequested            => "Disconnect requested"
        case Reasons.TcpSubsystemError              => "TCP sub-system error"
        case Reasons.UselessPeer                    => "Useless peer"
        case Reasons.TooManyPeers                   => "Too many peers"
        case Reasons.AlreadyConnected               => "Already connected"
        case Reasons.IncompatibleP2pProtocolVersion => "Incompatible P2P protocol version"
        case Reasons.NullNodeIdentityReceived       => "Null node identity received - this is automatically invalid"
        case Reasons.ClientQuitting                 => "Client quitting"
        case Reasons.UnexpectedIdentity             => "Unexpected identity"
        case Reasons.IdentityTheSame                => "Identity is the same as this node"
        case Reasons.TimeoutOnReceivingAMessage     => "Timeout on receiving a message"
        case Reasons.Other                          => "Some other reason specific to a subprotocol"
        case other                                  => s"unknown reason code: $other"
      }

    object DisconnectSerializers {
      implicit class DisconnectDec(val bytes: Array[Byte]) extends AnyVal {
        def toDisconnect: Disconnect = rawDecode(bytes) match {
          case RLPList(reason, _*) => Disconnect(reason = reason)
          case undecoded           => throw RLPException("Cannot decode Disconnect", undecoded)
        }
      }
    }

  }

  final case class Ping() extends Message {
    val code: Int = Ping.code

    def toRLPEncodable: RLPEncodeable = RLPList()
  }

  object Ping {
    val code = 0x02

    object PingSerializers {
      implicit class PingDec(val bytes: Array[Byte]) extends AnyVal {
        def toPing: Ping = Ping()
      }
    }
  }

  final case class Pong() extends Message {
    val code: Int = Pong.code

    def toRLPEncodable: RLPEncodeable = RLPList()
  }

  object Pong {
    val code = 0x03

    object PongSerializers {
      implicit class PongDec(val bytes: Array[Byte]) extends AnyVal {
        def toPong: Pong = Pong()
      }
    }
  }
}
