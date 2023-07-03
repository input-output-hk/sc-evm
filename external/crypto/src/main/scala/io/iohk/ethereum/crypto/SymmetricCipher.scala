package io.iohk.ethereum.crypto

import io.iohk.bytes.ByteString
import org.bouncycastle.crypto.BufferedBlockCipher
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.{CBCBlockCipher, SICBlockCipher}
import org.bouncycastle.crypto.paddings.{PKCS7Padding, PaddedBufferedBlockCipher}
import org.bouncycastle.crypto.params.{KeyParameter, ParametersWithIV}

import scala.util.Try

trait SymmetricCipher {
  def encrypt(secret: ByteString, iv: ByteString, message: ByteString): ByteString =
    process(true, secret, iv, message)

  def decrypt(secret: ByteString, iv: ByteString, encrypted: ByteString): Option[ByteString] =
    Try(process(false, secret, iv, encrypted)).toOption

  protected def getCipher: BufferedBlockCipher

  protected def process(forEncryption: Boolean, secret: ByteString, iv: ByteString, data: ByteString): ByteString = {
    val cipher = getCipher
    cipher.reset()
    val params = new ParametersWithIV(new KeyParameter(secret.toArray), iv.toArray)
    cipher.init(forEncryption, params)

    val size   = cipher.getOutputSize(data.size)
    val output = Array.ofDim[Byte](size)
    val offset = cipher.processBytes(data.toArray, 0, data.size, output, 0)
    val len    = cipher.doFinal(output, offset)

    ByteString(output).take(offset + len)
  }
}

object AES_CBC extends SymmetricCipher {
  protected def getCipher =
    new PaddedBufferedBlockCipher(new CBCBlockCipher(new AESEngine), new PKCS7Padding)
}

object AES_CTR extends SymmetricCipher {
  protected def getCipher =
    new BufferedBlockCipher(new SICBlockCipher(new AESEngine))
}
