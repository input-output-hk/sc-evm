package io.iohk.scevm.testing

import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{
  AccessListItem,
  LegacyTransaction,
  SignedTransaction,
  Transaction,
  TransactionHash,
  TransactionType01,
  TransactionType02,
  _
}
import io.iohk.scevm.testing.CryptoGenerators.{ecdsaKeyPairGen, fakeSignatureGen}
import io.iohk.scevm.testing.Generators.{addressGen, bigIntGen, byteStringOfLengthNGen}
import org.scalacheck.Gen

object TransactionGenerators {
  lazy val transactionHashGen: Gen[TransactionHash] = byteStringOfLengthNGen(32).map(TransactionHash(_))

  def accessListItemGen: Gen[AccessListItem] = for {
    address     <- addressGen
    storageKeys <- Gen.choose(0, 2).flatMap(Gen.listOfN(_, bigIntGen))
  } yield AccessListItem(address, storageKeys)

  def transactionGen: Gen[Transaction] =
    Gen.oneOf(legacyTransactionGen, typedTransactionGen)

  def typedTransactionGen: Gen[Transaction] =
    Gen.oneOf(transactionType01Gen, transactionType02Gen)

  def legacyTransactionGen: Gen[LegacyTransaction] = for {
    nonce            <- bigIntGen
    gasPrice         <- bigIntGen
    gasLimit         <- bigIntGen
    receivingAddress <- addressGen
    value            <- bigIntGen
    payload          <- byteStringOfLengthNGen(256)
  } yield LegacyTransaction(
    Nonce(nonce),
    gasPrice,
    gasLimit,
    receivingAddress,
    value,
    payload
  )

  private def transactionType01Gen: Gen[TransactionType01] = for {
    chainId          <- bigIntGen
    nonce            <- bigIntGen
    gasPrice         <- bigIntGen
    gasLimit         <- bigIntGen
    receivingAddress <- addressGen
    value            <- bigIntGen
    payload          <- byteStringOfLengthNGen(256)
    accessList       <- Gen.choose(0, 2).flatMap(Gen.listOfN(_, accessListItemGen))
  } yield TransactionType01(
    chainId,
    Nonce(nonce),
    gasPrice,
    gasLimit,
    receivingAddress,
    value,
    payload,
    accessList
  )

  private def transactionType02Gen: Gen[TransactionType02] = for {
    chainId              <- bigIntGen
    nonce                <- bigIntGen
    maxPriorityFeePerGas <- bigIntGen
    maxFeePerGas         <- bigIntGen
    gasLimit             <- bigIntGen
    receivingAddress     <- addressGen
    value                <- bigIntGen
    payload              <- byteStringOfLengthNGen(256)
    accessList           <- Gen.choose(0, 2).flatMap(Gen.listOfN(_, accessListItemGen))
  } yield TransactionType02(
    chainId,
    Nonce(nonce),
    maxPriorityFeePerGas,
    maxFeePerGas,
    gasLimit,
    receivingAddress,
    value,
    payload,
    accessList
  )

  def signedTxGenWithTx(tx: Transaction, chainId: Option[ChainId] = None): Gen[SignedTransaction] =
    for {
      (priv, _) <- ecdsaKeyPairGen
    } yield SignedTransaction.sign(tx, priv, chainId)

  def signedTxGen(chainId: Option[ChainId] = None): Gen[SignedTransaction] =
    transactionGen.flatMap(tx => signedTxGenWithTx(tx, chainId))

  def signedTxSeqGen(max: Int = 100, chainId: Option[ChainId] = None): Gen[List[SignedTransaction]] =
    Gen.listOfN(max, signedTxGen(chainId))

  def signedTxSeqGenFixed(size: Int, chainId: Option[ChainId] = None): Gen[List[SignedTransaction]] =
    Gen.containerOfN[List, SignedTransaction](size, signedTxGen(chainId))

  def invalidSignedTxSeqGen(maxLength: Int): Gen[Seq[SignedTransaction]] = {
    val fakeStxGen: Gen[SignedTransaction] =
      for {
        tx  <- transactionGen
        sig <- fakeSignatureGen
      } yield SignedTransaction(tx, sig)

    Gen.listOfN(maxLength, fakeStxGen)
  }

}
