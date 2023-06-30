package io.iohk.scevm.rpc.domain

import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.ObftBlock
import io.iohk.scevm.rpc.controllers.BlocksFixture
import io.iohk.scevm.testing.TestCoreConfigs
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class RpcBlockResponseSpec extends AnyWordSpec with Matchers with BlocksFixture {

  "serialize should raise an error as one of the transactions doesn't have a valid sender" in {
    val chainId: ChainId = TestCoreConfigs.chainId

    // Update the signature to make the transaction not having a sender
    val modifiedTransactionType01 =
      Block3125369.transactionType01.copy(signature = Block3125369.transactionType01.signature.copy(v = 0x9d.toByte))

    val blockForSerialisation: ObftBlock = ObftBlock(
      header = Block3125369.header,
      body = Block3125369.body.copy(transactionList =
        Seq(Block3125369.legacyTransaction, modifiedTransactionType01, Block3125369.transactionType02)
      )
    )

    val expectedError = JsonRpcError.SenderNotFound(modifiedTransactionType01.hash)

    val result = RpcBlockResponse.from(blockForSerialisation, chainId, fullTxs = true)

    result shouldBe Left(expectedError)
  }

}
