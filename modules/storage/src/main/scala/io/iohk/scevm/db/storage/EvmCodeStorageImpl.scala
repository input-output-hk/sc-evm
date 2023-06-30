package io.iohk.scevm.db.storage

import io.iohk.bytes.ByteString
import io.iohk.scevm.db.dataSource.{DataSource, DataSourceBatchUpdate}
import io.iohk.scevm.db.storage.EvmCodeStorageImpl._
import io.iohk.scevm.metrics.instruments
import io.prometheus.client

trait EvmCodeStorage {
  def get(key: CodeHash): Option[Code]
  def put(key: CodeHash, value: Code): DataSourceBatchUpdate
}

/** This class is used to store the EVM Code, by using:
  *   Key: hash of the code
  *   Value: the code
  */
class EvmCodeStorageImpl(
    val dataSource: DataSource,
    readHistogram: client.Histogram = instruments.Histogram.noOpClientHistogram(),
    writeHistogram: client.Histogram = instruments.Histogram.noOpClientHistogram()
) extends EvmCodeStorage
    with TransactionalKeyValueStorage[CodeHash, Code] {
  val namespace: IndexedSeq[Byte]                   = Namespaces.CodeNamespace
  def keySerializer: CodeHash => IndexedSeq[Byte]   = identity
  def keyDeserializer: IndexedSeq[Byte] => CodeHash = k => ByteString.fromArrayUnsafe(k.toArray)
  def valueSerializer: Code => IndexedSeq[Byte]     = identity
  def valueDeserializer: IndexedSeq[Byte] => Code   = (code: IndexedSeq[Byte]) => ByteString(code.toArray)

  /** This function obtains the associated value to a key in the current namespace, if there exists one.
    *
    * @param key
    * @return the value associated with the passed key, if there exists one.
    */
  override def get(key: CodeHash): Option[Code] = {
    val timer  = readHistogram.startTimer()
    val result = super.get(key)
    timer.observeDuration()
    result
  }

  override def put(key: CodeHash, value: Code): DataSourceBatchUpdate = {
    val result = super.put(key, value)
    val timer  = writeHistogram.startTimer()
    result.commit()
    timer.observeDuration()
    result
  }
}

object EvmCodeStorageImpl {
  type CodeHash = ByteString
  type Code     = ByteString
}
