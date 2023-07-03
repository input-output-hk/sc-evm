package io.iohk.scevm.network.p2p

import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto
import io.iohk.ethereum.crypto._
import io.iohk.scevm.network._
import io.iohk.scevm.network.rlpx.{AuthHandshakeSuccess, AuthHandshaker, Secrets}
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.util.encoders.Hex

import java.net.URI
import java.security.SecureRandom
import scala.annotation.nowarn

trait SecureChannelSetup {
  val secureRandom = new SecureRandom()

  val remoteNodeKey: AsymmetricCipherKeyPair      = generateKeyPair(secureRandom)
  val remoteEphemeralKey: AsymmetricCipherKeyPair = generateKeyPair(secureRandom)
  val remoteNonce: ByteString                     = randomNonce()
  val remoteNodeId: Array[Byte]                   = remoteNodeKey.getPublic.asInstanceOf[ECPublicKeyParameters].toNodeId
  val remoteUri                                   = new URI(s"enode://${Hex.toHexString(remoteNodeId)}@127.0.0.1:30303")

  val nodeKey: AsymmetricCipherKeyPair      = generateKeyPair(secureRandom)
  val ephemeralKey: AsymmetricCipherKeyPair = generateKeyPair(secureRandom)
  val nonce: ByteString                     = randomNonce()

  val handshaker: AuthHandshaker       = AuthHandshaker(nodeKey, nonce, ephemeralKey, secureRandom)
  val remoteHandshaker: AuthHandshaker = AuthHandshaker(remoteNodeKey, remoteNonce, remoteEphemeralKey, secureRandom)

  val (initPacket, handshakerInitiated) = handshaker.initiate(remoteUri)
  val (responsePacket, AuthHandshakeSuccess(remoteSecrets: Secrets, _)) =
    remoteHandshaker.handleInitialMessageV4(initPacket): @nowarn("msg=exhaustive")
  val AuthHandshakeSuccess(secrets: Secrets, _) =
    handshakerInitiated.handleResponseMessageV4(responsePacket): @nowarn("msg=exhaustive")

  def randomNonce(): ByteString = crypto.secureRandomByteString(secureRandom, AuthHandshaker.NonceSize)

}
