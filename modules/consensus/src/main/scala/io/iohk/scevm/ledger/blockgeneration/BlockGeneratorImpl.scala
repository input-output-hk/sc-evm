package io.iohk.scevm.ledger.blockgeneration

import cats.effect.Sync
import cats.implicits.toFunctorOps
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.ByteUtils
import io.iohk.scevm.consensus.pos.ObftBlockExecution.BlockExecutionResult
import io.iohk.scevm.domain._
import io.iohk.scevm.ledger.{BloomFilter, _}
import io.iohk.scevm.mpt.MptStorage
import io.iohk.scevm.utils.SystemTime.UnixTimestamp

class BlockGeneratorImpl[F[_]: Sync](blockPreparation: BlockPreparation[F]) extends BlockGenerator[F] {

  override def generateBlock(
      parent: ObftHeader,
      transactionList: Seq[SignedTransaction],
      slot: Slot,
      timestamp: UnixTimestamp,
      validatorPubKey: SidechainPublicKey,
      validatorPrvKey: SidechainPrivateKey
  ): F[ObftBlock] = {
    val blockContext: BlockContext =
      BlockContext(
        parent.hash,
        parent.number.next,
        slot,
        parent.gasLimit,
        Address.fromPublicKey(validatorPubKey),
        timestamp
      )

    blockPreparation.prepareBlock(blockContext, transactionList, parent).map {
      case PreparedBlock(selectedTransactions, BlockExecutionResult(_, gasUsed, receipts), stateRoot, _) =>
        val receiptsLogs: Seq[Array[Byte]] =
          BloomFilter.EmptyBloomFilter.toArray +: receipts.map(_.logsBloomFilter.toArray)

        ObftBlock(
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
    }

  }

}
