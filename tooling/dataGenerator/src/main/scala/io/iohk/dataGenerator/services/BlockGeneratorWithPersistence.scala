package io.iohk.dataGenerator.services

import cats.effect.IO
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.ByteUtils
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionResult
import io.iohk.scevm.db.storage.ReceiptStorage
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.vm.InMemoryWorldState
import io.iohk.scevm.ledger.blockgeneration.BlockGenerator
import io.iohk.scevm.ledger.{BlockPreparation, BloomFilter, PreparedBlock}
import io.iohk.scevm.mpt.MptStorage
import io.iohk.scevm.utils.SystemTime.UnixTimestamp

/** BlockGenerator implementation dedicated to dataGenerator:
  *   - generates a block (as [[io.iohk.scevm.ledger.blockgeneration.BlockGeneratorImpl]])
  *   - persists receipts and states
  * such that execution after consensus is not needed
  *
  * Copied and adapted from [[io.iohk.scevm.ledger.blockgeneration.BlockGeneratorImpl]]
  */
class BlockGeneratorWithPersistence(
    blockPreparation: BlockPreparation[IO],
    receiptStorage: ReceiptStorage[IO]
) extends BlockGenerator[IO] {

  override def generateBlock(
      parent: ObftHeader,
      transactionList: Seq[SignedTransaction],
      slot: Slot,
      timestamp: UnixTimestamp,
      validatorPubKey: SidechainPublicKey,
      validatorPrvKey: SidechainPrivateKey
  ): IO[ObftBlock] = {
    val blockContext: BlockContext =
      BlockContext(
        parent.hash,
        parent.number.next,
        slot,
        parent.gasLimit,
        Address.fromPublicKey(validatorPubKey),
        timestamp
      )

    blockPreparation.prepareBlock(blockContext, transactionList, parent).flatMap {
      case PreparedBlock(selectedTransactions, BlockExecutionResult(_, gasUsed, receipts), stateRoot, updatedWorld) =>
        val receiptsLogs: Seq[Array[Byte]] =
          BloomFilter.EmptyBloomFilter.toArray +: receipts.map(_.logsBloomFilter.toArray)

        val block = ObftBlock(
          header = blockContext
            .toUnsignedHeader(
              bloomFilter = ByteString(ByteUtils.or(receiptsLogs: _*)),
              gasUsed = gasUsed,
              receiptsRoot = MptStorage.rootHash(receipts, Receipt.byteArraySerializable),
              stateRoot = stateRoot,
              transactionsRoot = MptStorage.rootHash(selectedTransactions, SignedTransaction.byteArraySerializable),
              validatorPubKey = validatorPubKey
            )
            .sign(validatorPrvKey),
          body = ObftBody(selectedTransactions)
        )

        // Persists receipts and states
        for {
          _ <- receiptStorage.putReceipts(block.hash, receipts)
          _  = InMemoryWorldState.persistState(updatedWorld)
        } yield block
    }
  }
}
