package io.iohk.scevm.testing

import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.TransactionOutcome.HashOutcome
import io.iohk.scevm.domain.{
  Address,
  LeaderSlotEvent,
  LegacyReceipt,
  NotLeaderSlotEvent,
  Receipt,
  ReceiptBody,
  Slot,
  SlotEvent,
  Tick,
  Type01Receipt,
  UInt256,
  _
}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp
import org.scalacheck.{Arbitrary, Gen}

import java.math.BigInteger
import scala.annotation.tailrec

object Generators {

  lazy val byteGen: Gen[Byte] = Gen.choose(Byte.MinValue, Byte.MaxValue)

  lazy val shortGen: Gen[Short] = Gen.choose(Short.MinValue, Short.MaxValue)

  def intGen(min: Int, max: Int): Gen[Int] = Gen.choose(min, max)

  lazy val intGen: Gen[Int] = Gen.choose(Int.MinValue, Int.MaxValue)

  def longGen(min: Long, max: Long): Gen[Long] = Gen.choose(min, max)

  lazy val longGen: Gen[Long] = Gen.choose(Long.MinValue, Long.MaxValue)

  lazy val bigIntGen: Gen[BigInt] = byteArrayOfNItemsGen(32).map(b => new BigInteger(1, b))

  lazy val uInt256Gen: Gen[UInt256] = bigIntGen.map(UInt256.apply)

  lazy val tokenGen: Gen[Token] = bigIntGen.map(Token.apply)

  lazy val anyUnixTsGen: Gen[UnixTimestamp]               = Gen.choose(0, Int.MaxValue).map(_.millisToTs)
  def unixTsGen(min: Long, max: Long): Gen[UnixTimestamp] = Gen.choose(min, max).map(_.millisToTs)

  def randomSizeByteArrayGen(minSize: Int, maxSize: Int): Gen[Array[Byte]] =
    Gen.choose(minSize, maxSize).flatMap(byteArrayOfNItemsGen)

  def byteArrayOfNItemsGen(n: Int): Gen[Array[Byte]] = Gen.listOfN(n, Arbitrary.arbitrary[Byte]).map(_.toArray)

  def randomSizeByteStringGen(minSize: Int, maxSize: Int): Gen[ByteString] =
    Gen.choose(minSize, maxSize).flatMap(byteStringOfLengthNGen)

  def byteStringOfLengthNGen(n: Int): Gen[ByteString] = byteArrayOfNItemsGen(n).map(ByteString(_))

  def seqByteStringOfNItemsGen(n: Int): Gen[Seq[ByteString]] = Gen.listOf(byteStringOfLengthNGen(n))

  lazy val hexPrefixDecodeParametersGen: Gen[(Array[Byte], Boolean)] =
    for {
      aByteList <- Gen.nonEmptyListOf(Arbitrary.arbitrary[Byte])
      t         <- Arbitrary.arbitrary[Boolean]
    } yield (aByteList.toArray, t)

  def keyValueListGen(minValue: Int = Int.MinValue, maxValue: Int = Int.MaxValue): Gen[List[(Int, Int)]] =
    for {
      values   <- Gen.chooseNum(minValue, maxValue)
      aKeyList <- Gen.nonEmptyListOf(values).map(_.distinct)
    } yield aKeyList.zip(aKeyList)

  def keyValueByteStringGen(size: Int): Gen[List[(ByteString, Array[Byte])]] =
    for {
      byteStringList <- Gen.nonEmptyListOf(byteStringOfLengthNGen(size))
      arrayList      <- Gen.nonEmptyListOf(byteArrayOfNItemsGen(size))
    } yield byteStringList.zip(arrayList)

  def receiptGen: Gen[Receipt] = Gen.oneOf(legacyReceiptGen, type01ReceiptGen)

  private def legacyReceiptGen: Gen[LegacyReceipt] = receiptBodyGen.map(LegacyReceipt)
  private def type01ReceiptGen: Gen[Type01Receipt] = receiptBodyGen.map(Type01Receipt)

  private lazy val receiptBodyGen: Gen[ReceiptBody] = for {
    postTransactionStateHash <- byteArrayOfNItemsGen(32)
    cumulativeGasUsed        <- bigIntGen
    logsBloomFilter          <- byteArrayOfNItemsGen(256)
  } yield ReceiptBody(
    postTransactionStateHash = HashOutcome(ByteString(postTransactionStateHash)),
    cumulativeGasUsed = cumulativeGasUsed,
    logsBloomFilter = ByteString(logsBloomFilter),
    logs = Seq()
  )

  lazy val addressGen: Gen[Address] = byteArrayOfNItemsGen(20).map(Address(_))

  val blockNumberGen: Gen[BlockNumber] = bigIntGen.map(BlockNumber(_))

  def receiptsGen(n: Int): Gen[Seq[Seq[Receipt]]] = Gen.listOfN(n, Gen.nonEmptyListOf(receiptGen))

  lazy val evmCodeGen: Gen[ByteString] = byteStringOfLengthNGen(32)

  lazy val anySlotGen: Gen[Slot] = bigIntGen.map(Slot(_))

  lazy val anyTickGen: Gen[Tick] = for {
    slot      <- anySlotGen
    timestamp <- anyUnixTsGen
  } yield Tick(slot, timestamp)
  def slotMaxAfterGen(current: Slot, maxAfter: Int = 10): Gen[Slot] =
    intGen(1, maxAfter).map(random => Slot(current.number + random))

  lazy val notLeaderSlotEventGen: Gen[NotLeaderSlotEvent] = for {
    timestamp <- unixTsGen(1, Int.MaxValue)
    slot      <- anySlotGen
  } yield NotLeaderSlotEvent(slot, timestamp)

  lazy val leaderSlotEventGen: Gen[LeaderSlotEvent] = for {
    timestamp <- unixTsGen(1, Int.MaxValue)
    slot      <- anySlotGen
    keyPair   <- CryptoGenerators.ecdsaKeyPairGen
  } yield LeaderSlotEvent(slot, timestamp, LeaderSlotEvent.KeySet(keyPair._2, keyPair._1, Some(keyPair._1)))

  lazy val anySlotEventGen: Gen[SlotEvent] =
    Gen.oneOf(leaderSlotEventGen, notLeaderSlotEventGen)

  lazy val emptyStorageRoot: ByteString =
    Hex.decodeUnsafe("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421")

  /** Generates set of exactly 'size' as opposed to Gen.containerOfN[Set, T] that can generate smaller set. */
  def genSetOfN[T](size: Int, gen: Gen[T], stepsLimit: Int = 100): Gen[Set[T]] = {
    @tailrec
    def go(acc: Set[T], step: Int): Gen[Set[T]] =
      if (step >= stepsLimit) {
        throw new RuntimeException(
          s"Couldn't generate $size distinct elements in $step steps. Can you generator provide enough distinct values?"
        )
      } else {
        val xs        = Gen.listOfN(size, gen).sample.getOrElse(List.empty)
        val generated = (acc ++ xs).take(size)
        if (generated.size == size) Gen.const(generated)
        else go(generated, step + 1)
      }
    go(Set.empty, 0)
  }

}
