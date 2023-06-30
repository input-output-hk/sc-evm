package io.iohk.scevm.rpc.domain

import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSASignature
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain._
import io.iohk.scevm.rpc.domain.RpcLegacyTransactionResponse.RpcSpecLegacyTransactionResponse
import io.iohk.scevm.rpc.domain.RpcSigned1559TransactionResponse.RpcSpecSigned1559TransactionResponse
import io.iohk.scevm.rpc.domain.RpcSigned2930TransactionResponse.RpcSpecSigned2930TransactionResponse

sealed trait RpcFullTransactionResponse {
  def from: Address
  def nonce: Nonce
  def hash: ByteString
  def transactionIndex: BigInt
  def blockHash: ByteString
  def blockNumber: BigInt
  def gas: BigInt
  def input: ByteString
  def to: Option[ByteString]
  def value: BigInt
  def r: BigInt
  def s: BigInt
  def `type`: Byte
}

object RpcFullTransactionResponse {
  // scalastyle:off method.length
  def from(
      signedTransaction: SignedTransaction,
      transactionIndex: BigInt,
      blockHash: BlockHash,
      blockNumber: BlockNumber,
      chainId: ChainId
  ): Either[JsonRpcError, RpcFullTransactionResponse] = {
    val maybeResponse = SignedTransaction
      .getSender(signedTransaction)(chainId)
      .map { sender =>
        signedTransaction.transaction match {
          case transaction: TypedTransaction =>
            transaction match {
              case tx1: TransactionType01 =>
                RpcSigned2930TransactionResponse.from(
                  tx1,
                  signedTransaction.hash,
                  sender,
                  signedTransaction.signature,
                  transactionIndex,
                  blockHash,
                  blockNumber
                )
              case tx2: TransactionType02 =>
                RpcSigned1559TransactionResponse.from(
                  tx2,
                  signedTransaction.hash,
                  sender,
                  signedTransaction.signature,
                  transactionIndex,
                  blockHash,
                  blockNumber
                )
            }
          case tx: LegacyTransaction =>
            RpcLegacyTransactionResponse.from(
              tx,
              signedTransaction.hash,
              sender,
              signedTransaction.signature,
              transactionIndex,
              blockHash,
              blockNumber
            )
        }
      }

    maybeResponse match {
      case Some(response) => Right(response)
      case None           => Left(JsonRpcError.SenderNotFound(signedTransaction.hash))
    }
  }
  // scalastyle:on method.length
}

final case class RpcLegacyTransactionResponse(
    `type`: Byte = TypedTransaction.MinAllowedType,
    nonce: Nonce,
    hash: ByteString,
    from: Address,
    to: Option[ByteString],
    gas: BigInt,
    value: BigInt,
    input: ByteString,
    gasPrice: BigInt,
    chainId: Option[BigInt],
    v: BigInt,
    r: BigInt,
    s: BigInt,
    transactionIndex: BigInt,
    blockHash: ByteString,
    blockNumber: BigInt
) extends RpcFullTransactionResponse
    with RpcSpecLegacyTransactionResponse

object RpcLegacyTransactionResponse {
  private[scevm] trait RpcSpecLegacyTransactionResponse {
    def `type`: Byte
    def nonce: Nonce
    def hash: ByteString
    def from: Address
    def to: Option[ByteString]
    def gas: BigInt
    def value: BigInt
    def input: ByteString
    def gasPrice: BigInt
    def chainId: Option[BigInt]
    def v: BigInt
    def r: BigInt
    def s: BigInt
  }

  def from(
      tx: LegacyTransaction,
      txHash: TransactionHash,
      sender: Address,
      signature: ECDSASignature,
      transactionIndex: BigInt,
      blockHash: BlockHash,
      blockNumber: BlockNumber
  ): RpcLegacyTransactionResponse =
    RpcLegacyTransactionResponse(
      nonce = tx.nonce,
      hash = txHash.byteString,
      from = sender,
      to = tx.receivingAddress.map(_.bytes),
      gas = tx.gasLimit,
      value = tx.value,
      input = tx.payload,
      gasPrice = tx.gasPrice,
      chainId = None,
      v = signature.v,
      r = signature.r,
      s = signature.s,
      transactionIndex = transactionIndex,
      blockHash = blockHash.byteString,
      blockNumber = blockNumber.value
    )
}

final case class RpcSigned1559TransactionResponse(
    `type`: Byte = TypedTransaction.Type02,
    nonce: Nonce,
    hash: ByteString,
    from: Address,
    to: Option[ByteString],
    gas: BigInt,
    value: BigInt,
    input: ByteString,
    maxPriorityFeePerGas: BigInt,
    maxFeePerGas: BigInt,
    accessList: Seq[RpcAccessListItemResponse],
    chainId: BigInt,
    yParity: Byte,
    r: BigInt,
    s: BigInt,
    transactionIndex: BigInt,
    blockHash: ByteString,
    blockNumber: BigInt
) extends RpcFullTransactionResponse
    with RpcSpecSigned1559TransactionResponse

object RpcSigned1559TransactionResponse {
  private[scevm] trait RpcSpecSigned1559TransactionResponse {
    def `type`: Byte
    def nonce: Nonce
    def hash: ByteString
    def from: Address
    def to: Option[ByteString]
    def gas: BigInt
    def value: BigInt
    def input: ByteString
    def maxPriorityFeePerGas: BigInt
    def maxFeePerGas: BigInt
    def accessList: Seq[RpcAccessListItemResponse]
    def chainId: BigInt
    def yParity: Byte
    def r: BigInt
    def s: BigInt
  }

  def from(
      transaction: TransactionType02,
      txHash: TransactionHash,
      sender: Address,
      signature: ECDSASignature,
      transactionIndex: BigInt,
      blockHash: BlockHash,
      blockNumber: BlockNumber
  ): RpcSigned1559TransactionResponse =
    RpcSigned1559TransactionResponse(
      nonce = transaction.nonce,
      hash = txHash.byteString,
      from = sender,
      to = transaction.receivingAddress.map(_.bytes),
      gas = transaction.gasLimit,
      value = transaction.value,
      input = transaction.payload,
      maxPriorityFeePerGas = transaction.maxPriorityFeePerGas,
      maxFeePerGas = transaction.maxFeePerGas,
      accessList = transaction.accessList.map(RpcAccessListItemResponse.apply),
      chainId = transaction.chainId,
      yParity = signature.v,
      r = signature.r,
      s = signature.s,
      transactionIndex = transactionIndex,
      blockHash = blockHash.byteString,
      blockNumber = blockNumber.value
    )
}

final case class RpcSigned2930TransactionResponse(
    `type`: Byte = TypedTransaction.Type01,
    nonce: Nonce,
    hash: ByteString,
    from: Address,
    to: Option[ByteString],
    gas: BigInt,
    value: BigInt,
    input: ByteString,
    gasPrice: BigInt,
    accessList: Seq[RpcAccessListItemResponse],
    chainId: BigInt,
    yParity: Byte,
    r: BigInt,
    s: BigInt,
    transactionIndex: BigInt,
    blockHash: ByteString,
    blockNumber: BigInt
) extends RpcFullTransactionResponse
    with RpcSpecSigned2930TransactionResponse

object RpcSigned2930TransactionResponse {
  private[scevm] trait RpcSpecSigned2930TransactionResponse {
    def `type`: Byte
    def nonce: Nonce
    def hash: ByteString
    def from: Address
    def to: Option[ByteString]
    def gas: BigInt
    def value: BigInt
    def input: ByteString
    def gasPrice: BigInt
    def accessList: Seq[RpcAccessListItemResponse]
    def chainId: BigInt
    def yParity: Byte
    def r: BigInt
    def s: BigInt
  }

  def from(
      transaction: TransactionType01,
      txHash: TransactionHash,
      sender: Address,
      signature: ECDSASignature,
      transactionIndex: BigInt,
      blockHash: BlockHash,
      blockNumber: BlockNumber
  ): RpcSigned2930TransactionResponse =
    RpcSigned2930TransactionResponse(
      nonce = transaction.nonce,
      hash = txHash.byteString,
      from = sender,
      to = transaction.receivingAddress.map(_.bytes),
      gas = transaction.gasLimit,
      value = transaction.value,
      input = transaction.payload,
      gasPrice = transaction.gasPrice,
      accessList = transaction.accessList.map(RpcAccessListItemResponse.apply),
      chainId = transaction.chainId,
      yParity = signature.v,
      r = signature.r,
      s = signature.s,
      transactionIndex = transactionIndex,
      blockHash = blockHash.byteString,
      blockNumber = blockNumber.value
    )
}

final case class RpcAccessListItemResponse(address: Address, storageKeys: List[BigInt])
object RpcAccessListItemResponse {
  def apply(accessListItem: AccessListItem): RpcAccessListItemResponse =
    RpcAccessListItemResponse(accessListItem.address, accessListItem.storageKeys)
}
