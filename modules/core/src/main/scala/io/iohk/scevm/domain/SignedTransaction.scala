package io.iohk.scevm.domain

import cats.Show
import cats.implicits.showInterpolator
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignature, kec256}
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.serialization.ByteArraySerializable

import java.math.BigInteger
import scala.util.Try

final case class SignedTransaction(transaction: Transaction, signature: ECDSASignature) {

  import SignedTransaction.SignedTransactionRLPImplicits.SignedTransactionEnc

  override def toString: String =
    show"SignedTransaction(hash=$hash, transaction=$transaction, signature=$signature)"

  def safeSenderIsEqualTo(address: Address)(implicit chainId: ChainId): Boolean =
    SignedTransaction.getSender(this).contains(address)

  def isChainSpecific: Boolean =
    signature.v != ECDSASignature.negativePointSign && signature.v != ECDSASignature.positivePointSign

  lazy val hash: TransactionHash   = TransactionHash(ByteString(kec256(this.toBytes: Array[Byte])))
  lazy val hashAsHexString: String = hash.toHex
}

object SignedTransaction {
  implicit val show: Show[SignedTransaction] = Show.fromToString

  import io.iohk.ethereum.rlp.RLPImplicitConversions._
  import io.iohk.ethereum.rlp.RLPImplicits._
  import io.iohk.ethereum.rlp.{RLPEncodeable, RLPList, RLPSerializable, RLPValue, encode => rlpEncode, _}

  // txHash size is 32bytes, Address size is 20 bytes, taking into account some overhead key-val pair have
  // around 70bytes then 100k entries have around 7mb. 100k entries is around 300blocks for Ethereum network.
  val maximumSenderCacheSize = 100000

  val EIP155NegativePointSign = 35
  val EIP155PositivePointSign = 36
  val valueForEmptyR          = 0
  val valueForEmptyS          = 0

  def apply(
      tx: Transaction,
      pointSign: Byte,
      signatureRandom: ByteString,
      signature: ByteString
  ): SignedTransaction = {
    val txSignature = ECDSASignature(
      r = new BigInteger(1, signatureRandom.toArray),
      s = new BigInteger(1, signature.toArray),
      v = pointSign
    )
    SignedTransaction(tx, txSignature)
  }

  def sign(
      tx: Transaction,
      key: SidechainPrivateKey,
      chainId: Option[ChainId]
  ): SignedTransaction = {
    val bytes = bytesToSign(tx, chainId)
    val sig   = key.sign(ByteString(bytes))
    SignedTransaction(tx, getEthereumSignature(tx, sig, chainId))
  }

  private[domain] def bytesToSign(transaction: Transaction, chainIdOpt: Option[ChainId]): Array[Byte] =
    transaction match {
      case tx: LegacyTransaction => getLegacyTxBytesToSign(tx, chainIdOpt)
      case tx: TransactionType01 => getTypedTxBytesToSign(tx)
      case tx: TransactionType02 => getTypedTxBytesToSign(tx)
    }

  private def getLegacyTxBytesToSign(transaction: LegacyTransaction, chainIdOpt: Option[ChainId]): Array[Byte] =
    chainIdOpt match {
      case Some(id) =>
        chainSpecificTransactionBytes(transaction, id)
      case None =>
        generalTransactionBytes(transaction)
    }

  /** Transaction specific piece of code.
    * This should be moved to the Signer architecture once available.
    *
    * Convert a RLP compatible ECDSA Signature to a raw crypto signature.
    * Depending on the transaction type and the block number, different rules are
    * used to enhance the v field with additional context for signing purpose and networking
    * communication.
    *
    * Currently, both semantic data are represented by the same data structure.
    *
    * @see getEthereumSignature for the reciprocal conversion.
    * @param signedTransaction the signed transaction from which to extract the raw signature
    * @return a raw crypto signature, with only 27 or 28 as valid ECDSASignature.v value
    */
  private def getRawSignature(
      signedTransaction: SignedTransaction
  )(implicit chainId: ChainId): ECDSASignature =
    signedTransaction.transaction match {
      case _: LegacyTransaction =>
        val chainIdOpt = extractChainId(signedTransaction)
        getLegacyTxRawSignature(signedTransaction.signature, chainIdOpt)
      case _: TransactionType01 => getTypedTxRawSignature(signedTransaction.signature)
      case _: TransactionType02 => getTypedTxRawSignature(signedTransaction.signature)
    }

  /** LegacyTransaction specific piece of code.
    * This should be moved to the Signer architecture once available.
    *
    * Convert a LegacyTransaction RLP compatible ECDSA Signature to a raw crypto signature
    *
    * @param ethereumSignature the v-modified signature, received from the network
    * @param chainIdOpt        the chainId if available
    * @return a raw crypto signature, with only 27 or 28 as valid ECDSASignature.v value
    */
  private def getLegacyTxRawSignature(
      ethereumSignature: ECDSASignature,
      chainIdOpt: Option[ChainId]
  ): ECDSASignature =
    chainIdOpt match {
      // ignore chainId for unprotected negative y-parity in pre-eip155 signature
      case Some(_) if ethereumSignature.v == ECDSASignature.negativePointSign =>
        ethereumSignature.copy(v = ECDSASignature.negativePointSign)
      // ignore chainId for unprotected positive y-parity in pre-eip155 signature
      case Some(_) if ethereumSignature.v == ECDSASignature.positivePointSign =>
        ethereumSignature.copy(v = ECDSASignature.positivePointSign)
      // identify negative y-parity for protected post eip-155 signature
      case Some(ChainId(chainId)) if ethereumSignature.v == (2 * chainId + EIP155NegativePointSign).toByte =>
        ethereumSignature.copy(v = ECDSASignature.negativePointSign)
      // identify positive y-parity for protected post eip-155 signature
      case Some(ChainId(chainId)) if ethereumSignature.v == (2 * chainId + EIP155PositivePointSign).toByte =>
        ethereumSignature.copy(v = ECDSASignature.positivePointSign)
      // legacy pre-eip
      case None => ethereumSignature
      // unexpected chainId
      case _ =>
        throw new IllegalStateException(
          s"Unexpected pointSign for LegacyTransaction, chainId: ${chainIdOpt
            .getOrElse("None")}, ethereum.signature.v: ${ethereumSignature.v}"
        )
    }

  /** Transaction specific piece of code.
    * This should be moved to the Signer architecture once available.
    *
    * Convert a TransactionWithAccessList RLP compatible ECDSA Signature to a raw crypto signature
    *
    * @param ethereumSignature the v-modified signature, received from the network
    * @return a raw crypto signature, with only 27 or 28 as valid ECDSASignature.v value
    */
  private def getTypedTxRawSignature(ethereumSignature: ECDSASignature): ECDSASignature =
    ethereumSignature.v match {
      case 0 => ethereumSignature.copy(v = ECDSASignature.negativePointSign)
      case 1 => ethereumSignature.copy(v = ECDSASignature.positivePointSign)
      case _ =>
        throw new IllegalStateException(
          s"Unexpected pointSign for TransactionTypeX, ethereum.signature.v: ${ethereumSignature.v}"
        )
    }

  /** Transaction specific piece of code.
    * This should be moved to the Signer architecture once available.
    *
    * Convert a raw crypto signature into a RLP compatible ECDSA one.
    * Depending on the transaction type and the block number, different rules are
    * used to enhance the v field with additional context for signing purpose and networking
    * communication.
    *
    * Currently, both semantic data are represented by the same data structure.
    *
    * @see getRawSignature for the reciprocal conversion.
    * @param transaction           the transaction to adapt the raw signature to
    * @param rawSignature the raw signature generated by the crypto module
    * @param chainIdOpt   the chainId if available
    * @return a ECDSASignature with v value depending on the transaction type
    */
  private def getEthereumSignature(
      transaction: Transaction,
      rawSignature: ECDSASignature,
      chainIdOpt: Option[ChainId]
  ): ECDSASignature =
    transaction match {
      case _: LegacyTransaction =>
        getLegacyTxEthereumSignature(rawSignature, chainIdOpt)
      case _: TransactionType01 =>
        getTypedTxEthereumSignature(rawSignature)
      case _: TransactionType02 =>
        getTypedTxEthereumSignature(rawSignature)
    }

  /** LegacyTransaction specific piece of code.
    * This should be moved to the Signer architecture once available.
    *
    * Convert a raw crypto signature into a RLP compatible ECDSA one.
    *
    * @param rawSignature the raw signature generated by the crypto module
    * @param chainIdOpt        the chainId if available
    * @return a legacy transaction specific ECDSASignature, with v chainId-protected if possible
    */
  private def getLegacyTxEthereumSignature(rawSignature: ECDSASignature, chainIdOpt: Option[ChainId]): ECDSASignature =
    chainIdOpt match {
      case Some(ChainId(chainId)) if rawSignature.v == ECDSASignature.negativePointSign =>
        rawSignature.copy(v = (chainId * 2 + EIP155NegativePointSign).toByte)
      case Some(ChainId(chainId)) if rawSignature.v == ECDSASignature.positivePointSign =>
        rawSignature.copy(v = (chainId * 2 + EIP155PositivePointSign).toByte)
      case None => rawSignature
      case _ =>
        throw new IllegalStateException(
          s"Unexpected pointSign. ChainId: ${chainIdOpt.getOrElse("None")}, "
            + s"raw.signature.v: ${rawSignature.v}, "
            + s"authorized values are ${ECDSASignature.allowedPointSigns.mkString(", ")}"
        )
    }

  /** Transaction specific piece of code.
    * This should be moved to the Signer architecture once available.
    *
    * @param signedTransaction the signed transaction to get the chainId from
    * @return Some(chainId) if available, None if not (unprotected signed transaction)
    */
  private def extractChainId(
      signedTransaction: SignedTransaction
  )(implicit chainId: ChainId): Option[ChainId] =
    signedTransaction.transaction match {
      case _: LegacyTransaction if !signedTransaction.isChainSpecific => None
      case _: LegacyTransaction                                       => Some(chainId)
      case tx: TransactionType01                                      => Some(ChainId.unsafeFrom(tx.chainId))
      case tx: TransactionType02                                      => Some(ChainId.unsafeFrom(tx.chainId))
    }

  /** Transaction specific piece of code.
    * This should be moved to the Signer architecture once available.
    *
    * @param signedTransaction the signed transaction from which to extract the payload to sign
    * @return the payload to sign
    */
  private def getBytesToSign(
      signedTransaction: SignedTransaction
  )(implicit chainId: ChainId): Array[Byte] =
    signedTransaction.transaction match {
      case tx: LegacyTransaction =>
        val chainIdOpt = extractChainId(signedTransaction)
        chainIdOpt match {
          case None          => generalTransactionBytes(tx)
          case Some(chainId) => chainSpecificTransactionBytes(tx, chainId)
        }
      case tx: TransactionType01 => getTypedTxBytesToSign(tx)
      case tx: TransactionType02 => getTypedTxBytesToSign(tx)
    }

  /** Transaction specific piece of code.
    * This should be moved to the Signer architecture once available.
    *
    * Extract payload to sign for Transaction with access list
    *
    * @param tx
    * @return the transaction payload to sign for Transaction with access list
    */
  private def getTypedTxBytesToSign(transaction: TypedTransaction): Array[Byte] =
    crypto.kec256(
      rlpEncode(
        PrefixedRLPEncodable(
          transaction.getTransactionType,
          typedTxRlpContent(transaction)
        )
      )
    )

  /** Transaction specific piece of code.
    * This should be moved to the Signer architecture once available.
    *
    * Convert a raw crypto signature into a RLP compatible ECDSA one.
    *
    * @param rawSignature the raw signature generated by the crypto module
    * @return a transaction-with-access-list specific ECDSASignature
    */
  private def getTypedTxEthereumSignature(rawSignature: ECDSASignature): ECDSASignature =
    rawSignature match {
      case ECDSASignature(_, _, ECDSASignature.positivePointSign) =>
        rawSignature.copy(v = ECDSASignature.positiveYParity)
      case ECDSASignature(_, _, ECDSASignature.negativePointSign) =>
        rawSignature.copy(v = ECDSASignature.negativeYParity)
      case _ =>
        throw new IllegalStateException(
          s"Unexpected pointSign. raw.signature.v: ${rawSignature.v}, authorized values are ${ECDSASignature.allowedPointSigns
            .mkString(", ")}"
        )
    }

  def getSender(tx: SignedTransaction)(implicit chainId: ChainId): Option[Address] =
    Try {
      val bytesToSign: Array[Byte]                = getBytesToSign(tx)
      val recoveredPublicKey: Option[Array[Byte]] = getRawSignature(tx).publicKey(bytesToSign)
      for {
        keyBytes <- recoveredPublicKey
        key      <- ECDSA.PublicKey.fromBytes(ByteString(keyBytes)).toOption
      } yield Address.fromPublicKey(key)
    }.toOption.flatten

  /** Transaction specific piece of code.
    * This should be moved to the Signer architecture once available.
    *
    * Extract post-eip 155 payload to sign for legacy transaction
    *
    * @param transaction
    * @param chainId
    * @return the transaction payload for Legacy transaction
    */
  private def chainSpecificTransactionBytes(transaction: LegacyTransaction, chainId: ChainId): Array[Byte] =
    crypto.kec256(
      rlpEncode(
        legacyTxRlpContent(transaction) ++
          RLPList(chainId.value, valueForEmptyR, valueForEmptyS)
      )
    )

  /** Transaction specific piece of code.
    * This should be moved to the Signer architecture once available.
    *
    * Extract pre-eip 155 payload to sign for legacy transaction
    *
    * @param transaction
    * @return the transaction payload for Legacy transaction
    */
  private def generalTransactionBytes(transaction: LegacyTransaction): Array[Byte] =
    crypto.kec256(rlpEncode(legacyTxRlpContent(transaction)))

  val byteArraySerializable: ByteArraySerializable[SignedTransaction] = new ByteArraySerializable[SignedTransaction] {
    import SignedTransaction.SignedTransactionRLPImplicits._

    override def fromBytes(bytes: Array[Byte]): SignedTransaction = bytes.toSignedTransaction
    override def toBytes(input: SignedTransaction): Array[Byte]   = input.toBytes
  }

  private def typedTxRlpContent(transaction: TypedTransaction): RLPList = {
    val receivingAddress: Array[Byte] = transaction.receivingAddress.map(_.toArray).getOrElse(Array.emptyByteArray)

    val gasCosts = transaction match {
      case tx: TransactionType01 => RLPList(tx.gasPrice)
      case tx: TransactionType02 => RLPList(tx.maxPriorityFeePerGas, tx.maxFeePerGas)
    }

    RLPList(transaction.chainId, transaction.nonce) ++ gasCosts ++ RLPList(
      transaction.gasLimit,
      receivingAddress,
      transaction.value,
      transaction.payload,
      transaction.accessList
    )
  }

  def legacyTxRlpContent(transaction: LegacyTransaction): RLPList = {
    val receivingAddress: Array[Byte] = transaction.receivingAddress.map(_.toArray).getOrElse(Array.emptyByteArray)
    RLPList(
      transaction.nonce.value,
      transaction.gasPrice,
      transaction.gasLimit,
      receivingAddress,
      transaction.value,
      transaction.payload
    )
  }

  object SignedTransactionRLPImplicits {
    import AccessListItem.accessListItemCodec

    implicit class SignedTransactionEnc(val signedTransaction: SignedTransaction) extends RLPSerializable {
      override def toRLPEncodable: RLPEncodeable =
        signedTransaction.transaction match {
          case tx: LegacyTransaction =>
            legacyTxRlpContent(tx) ++
              RLPList(signedTransaction.signature.v, signedTransaction.signature.r, signedTransaction.signature.s)

          case tx: TransactionType01 =>
            PrefixedRLPEncodable(
              TypedTransaction.Type01,
              typedTxRlpContent(tx) ++
                RLPList(signedTransaction.signature.v, signedTransaction.signature.r, signedTransaction.signature.s)
            )

          case tx: TransactionType02 =>
            PrefixedRLPEncodable(
              TypedTransaction.Type02,
              typedTxRlpContent(tx) ++
                RLPList(signedTransaction.signature.v, signedTransaction.signature.r, signedTransaction.signature.s)
            )
        }
    }

    implicit class SignedTransactionRlpEncodableDec(val rlpEncodeable: RLPEncodeable) extends AnyVal {
      // scalastyle:off method.length
      /** A signed transaction is either a RLPList representing a Legacy SignedTransaction
        * or a PrefixedRLPEncodable(transactionType, RLPList of typed transaction envelope)
        *
        * @see TypedTransaction.TypedTransactionsRLPAggregator
        *
        * @return a SignedTransaction
        */
      def toSignedTransaction: SignedTransaction = rlpEncodeable match {
        case RLPList(
              nonce,
              gasPrice,
              gasLimit,
              receivingAddress: RLPValue,
              value,
              payload,
              pointSign,
              signatureRandom,
              signature
            ) =>
          val receivingAddressOpt = Option.when(receivingAddress.bytes.nonEmpty)(Address(receivingAddress.bytes))
          SignedTransaction(
            LegacyTransaction(
              Nonce(nonce),
              gasPrice,
              gasLimit,
              receivingAddressOpt,
              value,
              payload
            ),
            (pointSign: Int).toByte,
            signatureRandom,
            signature
          )
        case PrefixedRLPEncodable(
              TypedTransaction.Type01,
              RLPList(
                chainId,
                nonce,
                gasPrice,
                gasLimit,
                receivingAddress: RLPValue,
                value,
                payload,
                accessList: RLPList,
                pointSign,
                signatureRandom,
                signature
              )
            ) =>
          val receivingAddressOpt = Option.when(receivingAddress.bytes.nonEmpty)(Address(receivingAddress.bytes))
          SignedTransaction(
            TransactionType01(
              chainId,
              Nonce(nonce),
              gasPrice,
              gasLimit,
              receivingAddressOpt,
              value,
              payload,
              fromRlpList[AccessListItem](accessList).toList
            ),
            (pointSign: Int).toByte,
            signatureRandom,
            signature
          )
        case PrefixedRLPEncodable(
              TypedTransaction.Type02,
              RLPList(
                chainId,
                nonce,
                maxPriorityFeePerGas,
                maxFeePerGas,
                gasLimit,
                receivingAddress: RLPValue,
                value,
                payload,
                accessList: RLPList,
                pointSign,
                signatureRandom,
                signature
              )
            ) =>
          val receivingAddressOpt = Option.when(receivingAddress.bytes.nonEmpty)(Address(receivingAddress.bytes))
          SignedTransaction(
            TransactionType02(
              chainId,
              Nonce(nonce),
              maxPriorityFeePerGas,
              maxFeePerGas,
              gasLimit,
              receivingAddressOpt,
              value,
              payload,
              fromRlpList[AccessListItem](accessList).toList
            ),
            (pointSign: Int).toByte,
            signatureRandom,
            signature
          )
        case _ =>
          throw new RuntimeException(s"Cannot decode SignedTransaction from following rlpEncodeable: $rlpEncodeable")
      }
    }
    // scalastyle:on method.length

    implicit class SignedTransactionDec(val bytes: Array[Byte]) extends AnyVal {
      def toSignedTransaction: SignedTransaction = {
        val first = bytes(0)
        (first match {
          case TypedTransaction.Type01 => PrefixedRLPEncodable(TypedTransaction.Type01, rawDecode(bytes.tail))
          case TypedTransaction.Type02 => PrefixedRLPEncodable(TypedTransaction.Type02, rawDecode(bytes.tail))
          // TODO enforce legacy boundaries
          case _ => rawDecode(bytes)
        }).toSignedTransaction
      }
    }
  }
}
