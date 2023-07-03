package io.iohk.scevm.ledger

import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto._
import io.iohk.ethereum.utils.ByteUtils
import io.iohk.ethereum.utils.ByteUtils.or
import io.iohk.scevm.domain.TransactionLogEntry

object BloomFilter {

  val BloomFilterByteSize: Int             = 256
  private val BloomFilterBitSize: Int      = BloomFilterByteSize * 8
  val EmptyBloomFilter: ByteString         = ByteString(Array.fill(BloomFilterByteSize)(0.toByte))
  private val IntIndexesToAccess: Set[Int] = Set(0, 2, 4)

  def containsAnyOf(bloomFilterBytes: ByteString, toCheck: Seq[ByteString]): Boolean =
    toCheck.exists(contains(bloomFilterBytes, _))

  def contains(bloomFilterBytes: ByteString, toCheck: ByteString): Boolean = {
    val bloomFilterForBytes = bloomFilter(toCheck.toArray[Byte])

    val andResult = ByteUtils.and(bloomFilterForBytes, bloomFilterBytes.toArray[Byte])
    andResult.sameElements(bloomFilterForBytes)
  }

  /** Given the logs of a receipt creates the bloom filter associated with them
    * as stated in section 4.4.1 of the YP
    *
    * @param logs from the receipt whose bloom filter will be created
    * @return bloom filter associated with the logs
    */
  def create(logs: Seq[TransactionLogEntry]): ByteString = {
    val bloomFilters = logs.map(createBloomFilterForLogEntry)
    if (bloomFilters.isEmpty)
      EmptyBloomFilter
    else
      ByteString(or(bloomFilters: _*))
  }

  //Bloom filter function that reduces a log to a single 256-byte hash based on equation 24 from the YP
  private def createBloomFilterForLogEntry(logEntry: TransactionLogEntry): Array[Byte] = {
    val dataForBloomFilter = logEntry.loggerAddress.bytes +: logEntry.logTopics
    val bloomFilters       = dataForBloomFilter.map(bytes => bloomFilter(bytes.toArray))

    or(bloomFilters: _*)
  }

  def bloomFilter(bytes: ByteString): ByteString   = ByteString(bloomFilter(bytes.toArray))
  def bloomFilters(bytes: ByteString*): ByteString = ByteString(or(bytes.map(bytes => bloomFilter(bytes.toArray)): _*))

  //Bloom filter that sets 3 bits out of 2048 based on equations 25-28 from the YP
  private def bloomFilter(bytes: Array[Byte]): Array[Byte] = {
    val hashedBytes = kec256(bytes)
    val bitsToSet = IntIndexesToAccess.map { i =>
      val index16bit = (hashedBytes(i + 1) & 0xff) + ((hashedBytes(i) & 0xff) << 8)
      index16bit % BloomFilterBitSize //Obtain only 11 bits from the index
    }
    bitsToSet.foldLeft(EmptyBloomFilter.toArray) { case (prevBloom, index) => setBit(prevBloom, index) }.reverse
  }

  private def setBit(bytes: Array[Byte], bitIndex: Int): Array[Byte] = {
    require(bitIndex / 8 < bytes.length, "Only bits between the bytes array should be set")

    val byteIndex     = bitIndex / 8
    val newByte: Byte = (bytes(byteIndex) | 1 << (bitIndex % 8).toByte).toByte
    bytes.updated(byteIndex, newByte)
  }
}
