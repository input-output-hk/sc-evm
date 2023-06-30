package io.iohk.dataGenerator.services.transactions

import cats.effect.IO
import cats.syntax.all._
import io.iohk.dataGenerator.domain.{ContractCall, FundedAccount}
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{BlockNumber, ObftHeader, SignedTransaction, TransactionType01}

trait TransactionGenerator {

  def transactions(
      parent: ObftHeader,
      contracts: Seq[ContractCall],
      senders: Seq[FundedAccount]
  ): IO[Seq[SignedTransaction]] =
    if (parent.number == BlockNumber(0)) deployContracts(contracts, senders).pure[IO]
    else callContracts(parent, contracts, senders)

  protected def chainId: ChainId

  protected def deployContracts(contracts: Seq[ContractCall], senders: Seq[FundedAccount]): Seq[SignedTransaction] =
    for {
      sender   <- senders
      contract <- contracts
      initTransaction = TransactionType01(
                          chainId = chainId.value,
                          nonce = contract.nonce,
                          gasPrice = TransactionGenerator.gasPrice,
                          gasLimit = TransactionGenerator.gasLimit,
                          receivingAddress = None,
                          value = TransactionGenerator.value,
                          payload = contract.initCode,
                          accessList = List.empty
                        )
    } yield SignedTransaction.sign(initTransaction, sender.privateKey, Some(chainId))

  protected def callContracts(
      parent: ObftHeader,
      contracts: Seq[ContractCall],
      senders: Seq[FundedAccount],
      salt: Option[String] = None
  ): IO[Seq[SignedTransaction]]
}

object TransactionGenerator {
  val gasPrice: BigInt = 10
  val gasLimit: BigInt = 4_000_000
  val value: BigInt    = 0
}
