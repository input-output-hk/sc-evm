package io.iohk.scevm.db.storage

import io.iohk.bytes.ByteString
import io.iohk.scevm.db.dataSource.{DataSource, DataSourceUpdateOptimized}
import io.iohk.scevm.metrics.instruments
import io.iohk.scevm.mpt.{MptStorage, Node}
import io.prometheus.client

/** This class is used to store Nodes (defined in mpt/Node.scala), by using:
  *   Key: hash of the RLP encoded node
  *   Value: the RLP encoded node
  */
class NodeStorage(
    val dataSource: DataSource,
    readHistogram: client.Histogram = instruments.Histogram.noOpClientHistogram(),
    writeHistogram: client.Histogram = instruments.Histogram.noOpClientHistogram()
) extends MptStorage {

  val namespace: IndexedSeq[Byte]                         = Namespaces.NodeNamespace
  def keySerializer: Node.Hash => IndexedSeq[Byte]        = _.toIndexedSeq
  def keyDeserializer: IndexedSeq[Byte] => Node.Hash      = h => ByteString(h.toArray)
  def valueSerializer: Node.Encoded => IndexedSeq[Byte]   = _.toIndexedSeq
  def valueDeserializer: IndexedSeq[Byte] => Node.Encoded = _.toArray

  /** This function obtains the associated value to a key in the current namespace, if there exists one.
    *
    * @return the value associated with the passed key, if there exists one.
    */
  def get(key: Node.Hash): Option[Node.Encoded] = {
    val timer  = readHistogram.startTimer()
    val result = dataSource.getOptimized(namespace, key.toArray)
    timer.observeDuration()
    result
  }

  /** This function updates the KeyValueStorage by deleting, updating and inserting new (key-value) pairs
    * in the current namespace.
    *
    * @param toRemove which includes all the keys to be removed from the KeyValueStorage.
    * @param toUpsert which includes all the (key-value) pairs to be inserted into the KeyValueStorage.
    *                 If a key is already in the DataSource its value will be updated.
    * @return the new KeyValueStorage after the removals and insertions were done.
    */
  def update(toRemove: Seq[Node.Hash], toUpsert: Seq[(Node.Hash, Node.Encoded)]): NodeStorage = {
    val timer = writeHistogram.startTimer()
    dataSource.update(
      Seq(
        DataSourceUpdateOptimized(
          namespace = Namespaces.NodeNamespace,
          toRemove = toRemove.map(_.toArray),
          toUpsert = toUpsert.map(values => values._1.toArray -> values._2)
        )
      )
    )
    timer.observeDuration()
    this
  }
}
