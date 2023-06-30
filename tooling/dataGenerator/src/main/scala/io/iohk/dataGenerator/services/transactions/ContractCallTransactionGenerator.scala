package io.iohk.dataGenerator.services.transactions

import cats.effect.IO
import cats.syntax.all._
import io.iohk.dataGenerator.domain.{ContractCall, FundedAccount}
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.domain.{BlockContext, Nonce, ObftHeader, SignedTransaction, TransactionType01}
import io.iohk.scevm.ledger.NonceProvider

class ContractCallTransactionGenerator(
    override val chainId: ChainId,
    nonceProvider: NonceProvider[IO],
    worldStateBuilder: WorldStateBuilder[IO]
) extends TransactionGenerator {

  override def callContracts(
      parent: ObftHeader,
      contracts: Seq[ContractCall],
      senders: Seq[FundedAccount],
      salt: Option[String] = None
  ): IO[Seq[SignedTransaction]] =
    worldStateBuilder
      .getWorldStateForBlock(parent.stateRoot, BlockContext.from(parent))
      .use { worldState =>
        senders.flatTraverse { sender =>
          nonceProvider
            .getNextNonce(sender.toAddress, worldState)
            .map { nonce =>
              contracts.zipWithIndex.map { case (contract, index) =>
                val transaction = TransactionType01(
                  chainId = chainId.value,
                  nonce = Nonce(nonce.value + index),
                  gasPrice = TransactionGenerator.gasPrice,
                  gasLimit = TransactionGenerator.gasLimit,
                  receivingAddress = contract.contractAddress(sender.toAddress),
                  value = TransactionGenerator.value,
                  payload = contract.callCode,
                  accessList = List.empty
                )
                SignedTransaction.sign(transaction, sender.privateKey, Some(chainId))
              }

            }
        }
      }

}
