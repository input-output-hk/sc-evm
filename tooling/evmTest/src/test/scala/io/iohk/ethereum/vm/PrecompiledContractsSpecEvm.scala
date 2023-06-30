package io.iohk.ethereum.vm

import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto
import io.iohk.ethereum.crypto._
import io.iohk.ethereum.vm.utils.EvmTestEnv
import io.iohk.scevm.domain.Address
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.security.SecureRandom

class PrecompiledContractsSpecEvm extends AnyFunSuite with Matchers {

  test("Precompiled Contracts") {
    val secureRandom      = new SecureRandom()
    val (prvKey, pubKey)  = ECDSA.generateKeyPair(secureRandom)
    val bytes: ByteString = crypto.kec256(ByteString("aabbccdd"))
    val signature         = prvKey.sign(bytes)
    val address           = Address.fromPublicKey(pubKey).bytes
    val expectedOutput    = ripemd160(sha256(address))

    new EvmTestEnv {
      val (_, contract) = deployContract("PrecompiledContracts")
      val result        = contract.usePrecompiledContracts(bytes, signature.v, signature.r, signature.s).call()

      // even though contract specifies bytes20 as the return type, 32-byte (right zero padded) value is returned
      result.returnData.take(20) shouldEqual expectedOutput
    }
  }
}
