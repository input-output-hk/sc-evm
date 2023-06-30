package io.iohk.dataGenerator.services.transactions

import cats.effect.IO
import cats.syntax.all._
import io.iohk.bytes.ByteString
import io.iohk.dataGenerator.domain.{ContractCall, FundedAccount}
import io.iohk.dataGenerator.services.transactions.GenericContractTransactionGenerator._
import io.iohk.ethereum.crypto
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.domain._
import io.iohk.scevm.ledger.NonceProvider
import org.apache.commons.codec.binary.Base64
import org.scalacheck.rng.Seed
import org.scalacheck.{Arbitrary, Gen}

import java.math.BigInteger

class GenericContractTransactionGenerator(
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
        val seed = salt match {
          case Some(value) => computeSeed(parent.hash.byteString ++ ByteString(value))
          case None        => computeSeed(parent.hash.byteString)
        }

        sendersWithTransactionsGen(contracts, senders)
          .apply(Gen.Parameters.default, seed)
          .get
          .toSeq
          .traverse { case (sender, transactions) =>
            nonceProvider
              .getNextNonce(sender.toAddress, worldState)
              .map { nonce =>
                transactions.zipWithIndex.map { case (transactionFct, index) =>
                  val updatedNonce = Nonce(nonce.value + index)
                  SignedTransaction.sign(transactionFct(updatedNonce), sender.privateKey, Some(chainId))
                }
              }
          }
          .map(_.flatten)
      }

  private def sendersWithTransactionsGen(
      contracts: Seq[ContractCall],
      senders: Seq[FundedAccount]
  ): Gen[Map[FundedAccount, Seq[Nonce => Transaction]]] = {

    val senderWithContractsGen = for {
      sender            <- Gen.oneOf(senders)
      selectedContracts <- Gen.atLeastOne(contracts)
    } yield (
      sender,
      selectedContracts
        .map(contract => ContractTransaction(contract.contractAddress(sender.toAddress), contract.callCode))
        .toSeq
    )

    val senderWithRecipientsGen = for {
      sender              <- Gen.oneOf(senders)
      recipientsWithValue <- Gen.nonEmptyListOf(recipientWithValueGen)
    } yield (sender, recipientsWithValue)

    import scala.jdk.CollectionConverters._
    for {
      contractOrValueGen         <- Gen.oneOf(senderWithContractsGen, senderWithRecipientsGen)
      sendersWithContractOrValue <- Gen.listOf(contractOrValueGen)
      senderWithContractTransactions <-
        Gen.sequence(sendersWithContractOrValue.map { case (sender, contractsOrValues) =>
          Gen
            .sequence(contractsOrValues.distinct.map(transactionGen))
            .map(txs => (sender, txs.asScala.toSeq))
        })
    } yield senderWithContractTransactions.asScala.toMap
  }

  private def transactionGen(contractOrValue: ContractOrValue): Gen[Nonce => Transaction] =
    Gen.oneOf {
      List(
        (nonce: Nonce) =>
          LegacyTransaction(
            nonce = nonce,
            gasPrice = TransactionGenerator.gasPrice,
            gasLimit = TransactionGenerator.gasLimit,
            receivingAddress = contractOrValue.address,
            value = contractOrValue.value,
            payload = contractOrValue.callCode
          ),
        (nonce: Nonce) =>
          TransactionType01(
            chainId = chainId.value,
            nonce = nonce,
            gasPrice = TransactionGenerator.gasPrice,
            gasLimit = TransactionGenerator.gasLimit,
            receivingAddress = contractOrValue.address,
            value = contractOrValue.value,
            payload = contractOrValue.callCode,
            accessList = List.empty
          ),
        (nonce: Nonce) =>
          TransactionType02(
            chainId = chainId.value,
            nonce = nonce,
            maxPriorityFeePerGas = TransactionGenerator.gasPrice,
            maxFeePerGas = TransactionGenerator.gasPrice,
            gasLimit = TransactionGenerator.gasLimit,
            receivingAddress = contractOrValue.address,
            value = contractOrValue.value,
            payload = contractOrValue.callCode,
            accessList = List.empty
          )
      )
    }
}

object GenericContractTransactionGenerator {
  // scalastyle:off magic.number
  private def byteArrayOfNItemsGen(n: Int): Gen[Array[Byte]] = Gen.listOfN(n, Arbitrary.arbitrary[Byte]).map(_.toArray)
  private lazy val addressGen: Gen[Address]                  = byteArrayOfNItemsGen(20).map(Address(_))
  private lazy val valueGen: Gen[BigInt]                     = byteArrayOfNItemsGen(32).map(b => new BigInteger(1, b))
  // scalastyle:on magic.number

  private lazy val recipientWithValueGen: Gen[ValueTransaction] =
    addressGen.flatMap(recipient => valueGen.map(value => ValueTransaction(recipient, value)))

  private def computeSeed(input: ByteString) = {
    val digest  = crypto.sha256(input).toArray
    val seedStr = Base64.encodeBase64URLSafeString(digest).padTo(44, '=') // scalastyle:off magic.number
    Seed.fromBase64(seedStr).get
  }

  sealed trait ContractOrValue {
    def address: Address
    def value: BigInt
    def callCode: ByteString
  }
  final case class ContractTransaction(address: Address, callCode: ByteString) extends ContractOrValue {
    override def value: BigInt = 0
  }
  final case class ValueTransaction(recipient: Address, value: BigInt) extends ContractOrValue {
    override def address: Address = recipient

    override def callCode: ByteString = ByteString.empty
  }
}
