package io.iohk.scevm.network.p2p

import io.iohk.ethereum.rlp.RLPImplicitConversions._
import io.iohk.ethereum.rlp.RLPImplicits._
import io.iohk.ethereum.rlp.{RLPEncodeable, RLPException, RLPList, RLPSerializable, RLPValue, rawDecode}

sealed trait ProtocolFamily
object ProtocolFamily {
  final case object OBFT extends ProtocolFamily
  implicit class ProtocolFamilyEnc(val msg: ProtocolFamily) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable = msg match {
      case OBFT => RLPValue("obft".getBytes())
    }
  }
}

sealed abstract class Capability(val name: ProtocolFamily, val version: ProtocolVersions.Version)

object Capability {

  def parse(s: String): Option[Capability] = s match {
    case "obft/1" => Some(Capabilities.OBFT1)
    case _        => None // TODO: log unknown capability?
  }

  def parseUnsafe(s: String): Capability =
    parse(s).getOrElse(throw new RuntimeException(s"Capability $s not supported"))

  def negotiate(c1: List[Capability], c2: List[Capability]): Option[Capability] =
    c1.intersect(c2) match {
      case Nil => None
      case l   => Some(best(l))
    }

  //TODO consider how this scoring should be handled with 'snap' and other extended protocols
  def best(capabilities: List[Capability]): Capability =
    capabilities.maxBy(_.version)

  implicit class CapabilityEnc(val msg: Capability) extends RLPSerializable {
    override def toRLPEncodable: RLPEncodeable = RLPList(msg.name.toRLPEncodable, msg.version)
  }

  implicit class CapabilityDec(val bytes: Array[Byte]) extends AnyVal {
    def toCapability: Option[Capability] = CapabilityRLPEncodableDec(rawDecode(bytes)).toCapability
  }

  implicit class CapabilityRLPEncodableDec(val rLPEncodeable: RLPEncodeable) extends AnyVal {
    def toCapability: Option[Capability] = rLPEncodeable match {
      case RLPList(name, version) => parse(s"${stringEncDec.decode(name)}/${byteEncDec.decode(version)}")
      case _                      => throw new RLPException("Cannot decode Capability")
    }
  }

  object Capabilities {
    case object OBFT1 extends Capability(ProtocolFamily.OBFT, ProtocolVersions.PV1)

    val All: Seq[Capability] = Seq(OBFT1)
  }
}
