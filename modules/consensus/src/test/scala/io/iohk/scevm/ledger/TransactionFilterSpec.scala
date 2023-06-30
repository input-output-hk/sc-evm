package io.iohk.scevm.ledger

import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain._
import io.iohk.scevm.testing.CryptoGenerators
import io.iohk.scevm.testing.Generators.bigIntGen
import io.iohk.scevm.testing.TransactionGenerators.legacyTransactionGen
import org.scalacheck.Gen
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class TransactionFilterSpec extends AnyWordSpec with Matchers with MockFactory with ScalaCheckPropertyChecks {

  "TransactionProvider" when {

    "getPendingTransactions() is called" should {
      "return 0 transactions if their nonce is higher than the account's nonce" in {
        forAll(Gen.listOfN(5, senderWithTransactionsGen)) { input =>
          val sendersWithTransactions = input.distinctBy(_._1)

          // Given initialized accounts
          val accountProvider = sendersWithTransactions.map { case (senderAddress, transactionsStartingNonce, _) =>
            senderAddress -> Account(
              Nonce(transactionsStartingNonce.value - 1)
            ) // account is set one nonce before lowest transaction's nonce
          }.toMap

          val allTransactions = sendersWithTransactions.flatMap(_._3)

          // When
          val pendingTransactions =
            TransactionFilter
              .getPendingTransactions(ChainId(1), Nonce(0))(allTransactions, accountProvider.get)

          // Then
          pendingTransactions shouldBe empty
        }
      }

      "return all transactions if there are no gaps in their nonce" in {
        forAll(Gen.listOfN(5, senderWithTransactionsGen)) { input =>
          val sendersWithTransactions = input.distinctBy(_._1)

          // Given initialized accounts
          val accountProvider = sendersWithTransactions.map { case (senderAddress, transactionsStartingNonce, _) =>
            senderAddress -> Account(transactionsStartingNonce)
          }.toMap

          val allTransactions = sendersWithTransactions.flatMap(_._3)

          // When
          val pendingTransactions =
            TransactionFilter
              .getPendingTransactions(ChainId(1), Nonce(0))(allTransactions, accountProvider.get)

          // Then
          pendingTransactions.toSet shouldBe allTransactions.toSet
        }
      }

      "return first N transactions before the nonce gap" in {
        forAll(Gen.listOfN(5, transactionsWithNonceGapGen)) { input =>
          val sendersWithTransactions = input.distinctBy(_._1)

          // Given initialized accounts
          val accountProvider = sendersWithTransactions.map { case (senderAddress, transactionsStartingNonce, _, _) =>
            senderAddress -> Account(transactionsStartingNonce)
          }.toMap

          val allTransactions = sendersWithTransactions.flatMap {
            case (_, _, validNonceTransactions, futureNonceTransactions) =>
              validNonceTransactions ++ futureNonceTransactions
          }
          val allValidTransactions = sendersWithTransactions.flatMap(_._3)

          // When
          val pendingTransactions =
            TransactionFilter
              .getPendingTransactions(ChainId(1), Nonce(0))(allTransactions, accountProvider.get)

          // Then
          pendingTransactions.toSet shouldBe allValidTransactions.toSet
        }
      }

      "skip transactions with nonce < account.nonce" in {
        forAll(Gen.listOfN(5, transactionsWithPastNonceGen)) { input =>
          val sendersWithTransactions = input.distinctBy(_._1)

          // Given initialized accounts
          val accountProvider = sendersWithTransactions.map { case (senderAddress, transactionsStartingNonce, _, _) =>
            senderAddress -> Account(transactionsStartingNonce)
          }.toMap

          val allTransactions = sendersWithTransactions.flatMap {
            case (_, _, pastNonceTransactions, validNonceTransactions) =>
              pastNonceTransactions ++ validNonceTransactions
          }
          val allValidTransactions = sendersWithTransactions.flatMap(_._4)

          // When
          val pendingTransactions =
            TransactionFilter
              .getPendingTransactions(ChainId(1), Nonce(0))(allTransactions, accountProvider.get)

          // Then
          pendingTransactions.toSet shouldBe allValidTransactions.toSet
        }
      }
    }

    "getQueuedTransactions() is called" should {
      "return all transactions if their nonce is higher than the account's nonce" in {
        forAll(Gen.listOfN(5, senderWithTransactionsGen)) { input =>
          val sendersWithTransactions = input.distinctBy(_._1)

          // Given initialized accounts
          val accountProvider = sendersWithTransactions.map { case (senderAddress, transactionsStartingNonce, _) =>
            senderAddress -> Account(
              Nonce(transactionsStartingNonce.value - 1)
            ) // account is set one nonce before lowest transaction's nonce
          }.toMap

          val allTransactions = sendersWithTransactions.flatMap(_._3)

          // When
          val queuedTransactions =
            TransactionFilter
              .getQueuedTransactions(ChainId(1), Nonce(0))(allTransactions, accountProvider.get)

          // Then
          queuedTransactions.toSet shouldBe allTransactions.toSet
        }
      }

      "return 0 transactions if there are no gaps in their nonce" in {
        forAll(Gen.listOfN(5, senderWithTransactionsGen)) { input =>
          val sendersWithTransactions = input.distinctBy(_._1)
          // Given initialized accounts
          val accountProvider = sendersWithTransactions.map { case (senderAddress, transactionsStartingNonce, _) =>
            senderAddress -> Account(transactionsStartingNonce)
          }.toMap

          val allTransactions = sendersWithTransactions.flatMap(_._3)

          // When
          val queuedTransactions =
            TransactionFilter
              .getQueuedTransactions(ChainId(1), Nonce(0))(allTransactions, accountProvider.get)

          // Then
          queuedTransactions shouldBe empty
        }
      }

      "return N transactions after the nonce gap" in {
        forAll(Gen.listOfN(5, transactionsWithNonceGapGen)) { input =>
          val sendersWithTransactions = input.distinctBy(_._1)

          // Given initialized accounts
          val accountProvider = sendersWithTransactions.map { case (senderAddress, transactionsStartingNonce, _, _) =>
            senderAddress -> Account(transactionsStartingNonce)
          }.toMap

          val allTransactions = sendersWithTransactions.flatMap {
            case (_, _, validNonceTransactions, futureNonceTransactions) =>
              validNonceTransactions ++ futureNonceTransactions
          }
          val allFutureTransactions = sendersWithTransactions.flatMap(_._4)

          // When
          val queuedTransactions =
            TransactionFilter
              .getQueuedTransactions(ChainId(1), Nonce(0))(allTransactions, accountProvider.get)

          // Then
          queuedTransactions.toSet shouldBe allFutureTransactions.toSet
        }
      }

      "skip transactions with nonce < account.nonce" in {
        forAll(Gen.listOfN(5, transactionsWithPastNonceGen)) { input =>
          val sendersWithTransactions = input.distinctBy(_._1)

          // Given initialized accounts
          val accountProvider = sendersWithTransactions.map { case (senderAddress, transactionsStartingNonce, _, _) =>
            senderAddress -> Account(transactionsStartingNonce)
          }.toMap

          val allTransactions = sendersWithTransactions.flatMap {
            case (_, _, pastNonceTransactions, validNonceTransactions) =>
              pastNonceTransactions ++ validNonceTransactions
          }

          // When
          val queuedTransactions =
            TransactionFilter
              .getQueuedTransactions(ChainId(1), Nonce(0))(allTransactions, accountProvider.get)

          // Then
          queuedTransactions.toSet shouldBe empty
        }
      }
    }
  }

  private def signedTransactionsForSenderGen(
      senderPrvKey: ECDSA.PrivateKey,
      startNonce: Nonce
  ): Gen[List[SignedTransaction]] =
    for {
      size         <- Gen.chooseNum(0, 10)
      transactions <- Gen.listOfN(size, legacyTransactionGen)
      transactionsWithIncreasingNonce =
        transactions.zipWithIndex.map { case (transaction, index) =>
          transaction.copy(nonce = Nonce(startNonce.value + index))
        }
    } yield transactionsWithIncreasingNonce.map(SignedTransaction.sign(_, senderPrvKey, None))

  /** Generate a valid sequence of transactions for one sender. Nonces start at $startNonce and are consecutive and increasing.
    */
  private lazy val senderWithTransactionsGen: Gen[(Address, Nonce, List[SignedTransaction])] = for {
    (senderPrvKey, senderPubKey) <- CryptoGenerators.ecdsaKeyPairGen
    startNonce                   <- bigIntGen.map(Nonce.apply)
    signedTransactions           <- signedTransactionsForSenderGen(senderPrvKey, startNonce)
  } yield (Address.fromPublicKey(senderPubKey), startNonce, signedTransactions)

  /** Generate a sequence of transactions for one sender. Nonces start at $startNonce and are consecutive and increasing, except for one gap!
    * nonce ordering: startNonce | valid_txs[...] | gap | future_txs[...]
    */
  private lazy val transactionsWithNonceGapGen = for {
    (senderPrvKey, senderPubKey) <- CryptoGenerators.ecdsaKeyPairGen
    startNonce                   <- bigIntGen.map(Nonce.apply)
    validNonceTransactions       <- signedTransactionsForSenderGen(senderPrvKey, startNonce)
    futureNonceTransactions <-
      signedTransactionsForSenderGen(senderPrvKey, Nonce(startNonce.value + validNonceTransactions.size + 1))
  } yield (Address.fromPublicKey(senderPubKey), startNonce, validNonceTransactions, futureNonceTransactions)

  /** Generate two sequences of transactions for one sender: past transactions and valid transactions.
    * nonce ordering: past_txs[...] | gap | startNonce | valid_txs[...]
    */
  private lazy val transactionsWithPastNonceGen = for {
    (senderPrvKey, senderPubKey) <- CryptoGenerators.ecdsaKeyPairGen
    startNonce                   <- bigIntGen.map(Nonce.apply)
    pastNonceTransactions        <- signedTransactionsForSenderGen(senderPrvKey, Nonce(startNonce.value - 100))
    validNonceTransactions       <- signedTransactionsForSenderGen(senderPrvKey, startNonce)
  } yield (Address.fromPublicKey(senderPubKey), startNonce, pastNonceTransactions, validNonceTransactions)
}
