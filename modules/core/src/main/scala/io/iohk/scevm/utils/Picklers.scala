package io.iohk.scevm.utils

import boopickle.DefaultBasic._
import boopickle.Pickler
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.ECDSASignature
import io.iohk.scevm.domain.{
  AccessListItem,
  Address,
  LegacyTransaction,
  ObftHeader,
  Slot,
  Transaction,
  TransactionType01,
  TransactionType02,
  _
}
import io.iohk.scevm.serialization.Newtype

object Picklers {
  implicit def valueClassPickler[Wrapper, Underlying](implicit
      valueClass: Newtype[Wrapper, Underlying],
      pickler: Pickler[Underlying]
  ): Pickler[Wrapper] = pickler.xmap(valueClass.wrap)(valueClass.unwrap)

  implicit val byteStringPickler: Pickler[ByteString] =
    transformPickler[ByteString, Array[Byte]](ByteString(_))(_.toArray[Byte])
  implicit val ecdsaSignaturePickler: Pickler[ECDSASignature] = generatePickler[ECDSASignature]

  implicit val addressPickler: Pickler[Address] =
    transformPickler[Address, ByteString](bytes => Address(bytes))(address => address.bytes)
  implicit val accessListItemPickler: Pickler[AccessListItem] = generatePickler[AccessListItem]

  implicit val legacyTransactionPickler: Pickler[LegacyTransaction] = generatePickler[LegacyTransaction]
  implicit val transactionType01Pickler: Pickler[TransactionType01] =
    generatePickler[TransactionType01]
  implicit val transactionType02Pickler: Pickler[TransactionType02] =
    generatePickler[TransactionType02]

  implicit val transactionPickler: Pickler[Transaction] = compositePickler[Transaction]
    .addConcreteType[LegacyTransaction]
    .addConcreteType[TransactionType01]
    .addConcreteType[TransactionType02]

  implicit val signedTransactionPickler: Pickler[SignedTransaction] =
    transformPickler[SignedTransaction, (Transaction, ECDSASignature)] { case (tx, signature) =>
      new SignedTransaction(tx, signature)
    }(stx => (stx.transaction, stx.signature))

  implicit val slotPickler: Pickler[Slot]                  = generatePickler[Slot]
  implicit val obftBlockHeaderPickler: Pickler[ObftHeader] = generatePickler[ObftHeader]
  implicit val obftBlockBodyPickler: Pickler[ObftBody] =
    transformPickler[ObftBody, Seq[SignedTransaction]] { stx =>
      ObftBody(stx)
    }(body => body.transactionList)
}
