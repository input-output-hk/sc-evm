package io.iohk.scevm.cardanofollower.testing

import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.plutus.{ByteStringDatum, ConstructorDatum, Datum, DatumMapItem, IntegerDatum, ListDatum, MapDatum}
import io.iohk.scevm.testing.BlockGenerators.blockHashGen
import io.iohk.scevm.testing.Generators
import io.iohk.scevm.trustlesssidechain.cardano.{
  AssetName,
  MainchainAddress,
  MainchainBlockInfo,
  MainchainBlockNumber,
  MainchainEpoch,
  MainchainSlot,
  MainchainTxHash,
  MainchainTxOutput,
  MintAction,
  SpentByInfo,
  UtxoId
}
import org.scalacheck.Gen

import java.time.Instant

object CardanoFollowerGenerators {

  private val nonNegativeLong = Generators.longGen(0, Long.MaxValue)
  private val nonNegativeInt  = Generators.intGen(0, Int.MaxValue)

  val mainchainTxHashGen: Gen[MainchainTxHash]           = Generators.byteStringOfLengthNGen(32).map(MainchainTxHash.apply)
  val mainchainBlockNumberGen: Gen[MainchainBlockNumber] = nonNegativeLong.map(MainchainBlockNumber(_))
  val mainchainSlotGen: Gen[MainchainSlot]               = nonNegativeLong.map(MainchainSlot(_))
  val mainchainEpochGen: Gen[MainchainEpoch]             = nonNegativeLong.map(MainchainEpoch(_))

  val mainchainAddressGen: Gen[MainchainAddress] = for {
    length <- Generators.intGen(32, 64)
    bytes  <- Generators.byteStringOfLengthNGen(length)
  } yield MainchainAddress("addr_" + Hex.toHexString(bytes))

  val utxoIdGen: Gen[UtxoId] = for {
    txHash <- mainchainTxHashGen
    idx    <- Generators.intGen(0, Int.MaxValue)
  } yield UtxoId(txHash, idx)

  val spentByInfoGen: Gen[SpentByInfo] = for {
    txHash <- mainchainTxHashGen
    epoch  <- mainchainEpochGen
    slot   <- mainchainSlotGen
  } yield SpentByInfo(txHash, epoch, slot)

  // TODO: move to plutus project

  val integerDatumGen: Gen[IntegerDatum] = Generators.bigIntGen.map(IntegerDatum(_))

  val byteStringDatumGen: Gen[ByteStringDatum] = for {
    length <- Generators.intGen(0, 100)
    bytes  <- Generators.byteStringOfLengthNGen(length)
  } yield ByteStringDatum(bytes)

  def constructorDatumGen(depth: Int): Gen[ConstructorDatum] = for {
    constructor <- nonNegativeInt
    fields      <- Gen.nonEmptyListOf(datumGen(depth))
  } yield ConstructorDatum(constructor, fields.toVector)

  def listDatumGen(depth: Int): Gen[ListDatum] = for {
    length <- Generators.intGen(0, 10)
    datums <- Gen.listOfN(length, datumGen(depth))
  } yield ListDatum(datums.toVector)

  def datumMapItemGen(depth: Int): Gen[DatumMapItem] = for {
    key   <- Gen.lzy(datumGen(depth))
    value <- Gen.lzy(datumGen(depth))
  } yield DatumMapItem(key, value)

  def mapDatumGen(depth: Int): Gen[MapDatum] = for {
    size  <- Generators.intGen(0, 10)
    items <- Gen.listOfN(size, datumMapItemGen(depth))
  } yield MapDatum(items.toVector)

  /** @param depth - recursion depth counter to prevent creating to big instances and stack overflow error
    * @return random Datum
    */
  def datumGen(depth: Int = 0): Gen[Datum] = if (depth >= 3)
    Gen.oneOf(integerDatumGen, byteStringDatumGen)
  else
    Gen.oneOf(
      integerDatumGen,
      byteStringDatumGen,
      constructorDatumGen(depth + 1),
      listDatumGen(depth + 1),
      mapDatumGen(depth + 1)
    )

  val mainchainTxOutputGen: Gen[MainchainTxOutput] = for {
    id       <- utxoIdGen
    block    <- mainchainBlockNumberGen
    slot     <- mainchainSlotGen
    epoch    <- mainchainEpochGen
    idx      <- nonNegativeInt
    address  <- mainchainAddressGen
    datum    <- Gen.option(datumGen(0))
    spentBy  <- Gen.option(spentByInfoGen)
    txInputs <- Gen.listOf(utxoIdGen)
  } yield MainchainTxOutput(
    id = id,
    blockNumber = block,
    slotNumber = slot,
    epochNumber = epoch,
    txIndexInBlock = idx,
    address = address,
    datum = datum,
    spentBy = spentBy,
    txInputs = txInputs
  )

  val mainchainBlockInfoGen: Gen[MainchainBlockInfo] = for {
    number     <- mainchainBlockNumberGen
    hash       <- blockHashGen
    epoch      <- mainchainEpochGen
    slot       <- mainchainSlotGen
    epochMilli <- Generators.longGen(0, Instant.parse("2030-01-01T00:00:00Z").toEpochMilli)
  } yield MainchainBlockInfo(number, hash, epoch, slot, Instant.ofEpochMilli(epochMilli))

  val assetNameGen: Gen[AssetName] = for {
    length <- Generators.intGen(0, 100)
    bytes  <- Generators.byteStringOfLengthNGen(length)
  } yield AssetName(bytes)

  val mintActionGen: Gen[MintAction] = for {
    txHash         <- mainchainTxHashGen
    assetName      <- assetNameGen
    blockNumber    <- mainchainBlockNumberGen
    txIndexInBlock <- Generators.intGen(0, 1000)
    redeemer       <- Gen.option(datumGen(0))
  } yield MintAction(txHash, assetName, blockNumber, txIndexInBlock, redeemer)
}
