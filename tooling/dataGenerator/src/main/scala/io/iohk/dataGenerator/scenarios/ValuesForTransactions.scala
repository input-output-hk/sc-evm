package io.iohk.dataGenerator.scenarios

import io.iohk.bytes.ByteString
import io.iohk.dataGenerator.domain.{ContractCall, FundedAccount}
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.Nonce

import java.io.File
import scala.io.Source

object ValuesForTransactions {

  val senders: Seq[FundedAccount] = Seq(
    // must be allocated in genesis.json
    {
      val prvKey = ECDSA.PrivateKey.fromHexUnsafe("6f2797ceb1855ab1589c113eeec16f42f781374cb0a44ff73cce36aa9acecd9f")
      FundedAccount(prvKey)
    }, {
      val prvKey = ECDSA.PrivateKey.fromHexUnsafe("42600fbbf295b9692484080953247a085c2e9b3ce7c820566569271341bd1cc0")
      FundedAccount(prvKey)
    }, {
      val prvKey = ECDSA.PrivateKey.fromHexUnsafe("a59f31595ee85087f029db2681025a5e5e2051bd474e2ead8c198d3bcc0ec25a")
      FundedAccount(prvKey)
    }
  )

  // scalastyle:off
  lazy val contracts: Seq[ContractCall] =
    // Map: contract binary -> contract call code
    Map(
      "tooling/dataGenerator/target/contracts/Counter.bin"  -> "371303c0", // returned by SolidityAbi.solidityCall("inc")
      "tooling/dataGenerator/target/contracts/CoinFlip.bin" -> "4cb48bcb000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000047472756500000000000000000000000000000000000000000000000000000000" // returned by SolidityAbi.solidityCall("flip", "true")
    )
      .map { case (initCodeFile, rawCallCode) =>
        (loadContractCodeFromFile(new File(initCodeFile)), Hex.decodeUnsafe(rawCallCode))
      }
      .zipWithIndex
      .map { case ((initCode, callCode), index) => ContractCall(initCode, callCode, Nonce(index)) }
      .toSeq
  // scalastyle:on

  private def loadContractCodeFromFile(file: File): ByteString = {
    val src = Source.fromFile(file)
    val raw =
      try src.mkString
      finally src.close()
    Hex.decodeUnsafe(raw)
  }

}
