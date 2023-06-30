package io.iohk.scevm.domain

import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.kec256
import org.bouncycastle.util.encoders.Hex

final case class Account(
    nonce: Nonce = Nonce.Zero,
    balance: UInt256 = 0,
    storageRoot: ByteString = Account.EmptyStorageRootHash,
    codeHash: ByteString = Account.EmptyCodeHash
) {

  def increaseBalance(value: UInt256): Account =
    copy(balance = balance + value)

  def increaseNonceOnce: Account =
    copy(nonce = nonce.increaseOne)

  def withCode(codeHash: ByteString): Account =
    copy(codeHash = codeHash)

  def withStorage(storageRoot: ByteString): Account =
    copy(storageRoot = storageRoot)

  /** According to EIP161: An account is considered empty when it has no code and zero nonce and zero balance.
    * An account's storage is not relevant when determining emptiness.
    */
  def isEmpty(startNonce: Nonce = Nonce.Zero): Boolean =
    nonce == startNonce && balance == UInt256.Zero && codeHash == Account.EmptyCodeHash

  /** Under EIP-684 if this evaluates to true then we have a conflict when creating a new account
    */
  def nonEmptyCodeOrNonce(startNonce: Nonce = Nonce.Zero): Boolean =
    nonce != startNonce || codeHash != Account.EmptyCodeHash

  override def toString: String =
    s"Account(nonce: $nonce, balance: $balance, " +
      s"storageRoot: ${Hex.toHexString(storageRoot.toArray[Byte])}, codeHash: ${Hex.toHexString(codeHash.toArray[Byte])})"

}
object Account {
  import io.iohk.ethereum.rlp._
  import io.iohk.ethereum.rlp.RLPSerializable
  import io.iohk.ethereum.rlp.RLPEncodeable
  import io.iohk.ethereum.rlp.RLPList
  import io.iohk.ethereum.rlp.RLPImplicitConversions._
  import io.iohk.ethereum.rlp.RLPImplicits._
  import io.iohk.scevm.serialization.ByteArraySerializable

  val EmptyStorageRootHash: ByteString = ByteString(kec256(encode(Array.empty[Byte])))
  val EmptyCodeHash: ByteString        = kec256(ByteString())

  def empty(startNonce: Nonce = Nonce.Zero): Account =
    Account(nonce = startNonce, storageRoot = EmptyStorageRootHash, codeHash = EmptyCodeHash)

  implicit val accountSerializer: ByteArraySerializable[Account] = new ByteArraySerializable[Account] {

    import AccountRLPImplicits._

    override def fromBytes(bytes: Array[Byte]): Account = bytes.toAccount
    override def toBytes(input: Account): Array[Byte]   = input.toBytes
  }

  object AccountRLPImplicits {
    import UInt256.UInt256RLPImplicits._
    import Nonce._

    implicit class AccountEnc(val account: Account) extends RLPSerializable {
      override def toRLPEncodable: RLPEncodeable = {
        import account._
        RLPList(nonce, balance.toRLPEncodable, storageRoot, codeHash)
      }
    }

    implicit class AccountDec(val bytes: Array[Byte]) extends AnyVal {
      def toAccount: Account = rawDecode(bytes) match {
        case RLPList(nonce, balance, storageRoot, codeHash) =>
          Account(decode[Nonce](nonce), balance.toUInt256, storageRoot, codeHash)
        case _ => throw new RuntimeException("Cannot decode Account")
      }
    }
  }
}
