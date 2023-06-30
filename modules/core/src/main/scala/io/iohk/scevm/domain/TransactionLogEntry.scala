package io.iohk.scevm.domain

import io.iohk.bytes.ByteString
import org.bouncycastle.util.encoders.Hex

final case class TransactionLogEntry(loggerAddress: Address, logTopics: Seq[ByteString], data: ByteString) {
  override def toString: String =
    s"${getClass.getSimpleName}(" +
      s"loggerAddress: $loggerAddress, " +
      s"logTopics: ${logTopics.map(e => Hex.toHexString(e.toArray[Byte])).mkString("[", ", ", "]")}, " +
      s"data: ${Hex.toHexString(data.toArray[Byte])}" + ")"
}

object TransactionLogEntry {
  object TransactionLogEntryRLPImplicits {
    import io.iohk.ethereum.rlp.RLPSerializable
    import io.iohk.ethereum.rlp.RLPEncodeable
    import io.iohk.ethereum.rlp.RLPList
    import io.iohk.ethereum.rlp.RLPImplicitConversions._
    import io.iohk.ethereum.rlp.RLPImplicits._
    implicit class TransactionLogEntryEnc(logEntry: TransactionLogEntry) extends RLPSerializable {
      override def toRLPEncodable: RLPEncodeable = {
        import logEntry._
        RLPList(loggerAddress.bytes, logTopics, data)
      }
    }

    implicit class TransactionLogEntryDec(rlp: RLPEncodeable) {
      def toTransactionLogEntry: TransactionLogEntry = rlp match {
        case RLPList(loggerAddress, logTopics: RLPList, data) =>
          TransactionLogEntry(Address(loggerAddress: ByteString), fromRlpList[ByteString](logTopics), data)

        case _ => throw new RuntimeException("Cannot decode TransactionLog")
      }
    }
  }
}
