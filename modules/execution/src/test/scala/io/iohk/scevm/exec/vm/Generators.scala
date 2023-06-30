package io.iohk.scevm.exec.vm

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Account, Address, BlockNumber, BlocksDistance, UInt256}
import io.iohk.scevm.exec.config.EvmConfig
import io.iohk.scevm.exec.vm.MockWorldState._
import io.iohk.scevm.testing.fixtures._
import org.scalacheck.{Arbitrary, Gen}

import fixtures.blockchainConfig

// scalastyle:off magic.number
object Generators {
  val testStackMaxSize = 32

  def getListGen[T](minSize: Int, maxSize: Int, genT: Gen[T]): Gen[List[T]] =
    Gen.choose(minSize, maxSize).flatMap(size => Gen.listOfN(size, genT))

  def getByteStringGen(minSize: Int, maxSize: Int, byteGen: Gen[Byte] = Arbitrary.arbitrary[Byte]): Gen[ByteString] =
    getListGen(minSize, maxSize, byteGen).map(l => ByteString(l.toArray))

  def getBigIntGen(min: BigInt = 0, max: BigInt = BigInt(2).pow(256) - 1): Gen[BigInt] = {
    val mod    = max - min
    val nBytes = mod.bitLength / 8 + 1
    for {
      bytes <- getByteStringGen(nBytes, nBytes)
      bigInt = (if (mod > 0) BigInt(bytes.toArray).abs % mod else BigInt(0)) + min
    } yield bigInt
  }

  def getUInt256Gen(min: UInt256 = UInt256(0), max: UInt256 = UInt256.MaxValue): Gen[UInt256] =
    getBigIntGen(min.toBigInt, max.toBigInt).map(UInt256(_))

  def getBlockNumberGen(
      min: BlockNumber = BlockNumber(0),
      max: BlockNumber = BlockNumber(UInt256.MaxValue.toBigInt)
  ): Gen[BlockNumber] =
    getBigIntGen(min.value, max.value).map(BlockNumber(_))

  def getStackGen(
      minElems: Int = 0,
      maxElems: Int = testStackMaxSize,
      valueGen: Gen[UInt256] = getUInt256Gen(),
      maxSize: Int = testStackMaxSize
  ): Gen[Stack] =
    for {
      size <- Gen.choose(minElems, maxElems)
      list <- Gen.listOfN(size, valueGen)
      stack = Stack.empty(maxSize)
    } yield stack.push(list)

  def getStackGen(elems: Int, uint256Gen: Gen[UInt256]): Gen[Stack] =
    getStackGen(minElems = elems, maxElems = elems, uint256Gen)

  def getStackGen(elems: Int): Gen[Stack] =
    getStackGen(minElems = elems, maxElems = elems, getUInt256Gen())

  def getStackGen(elems: Int, maxUInt: UInt256): Gen[Stack] =
    getStackGen(minElems = elems, maxElems = elems, valueGen = getUInt256Gen(max = maxUInt), maxSize = testStackMaxSize)

  def getStackGen(maxWord: UInt256): Gen[Stack] =
    getStackGen(valueGen = getUInt256Gen(max = maxWord), maxSize = testStackMaxSize)

  def getMemoryGen(maxSize: Int = 0): Gen[Memory] =
    getByteStringGen(0, maxSize).map(Memory.empty.store(0, _))

  def getStorageGen(maxSize: Int = 0, uint256Gen: Gen[UInt256] = getUInt256Gen()): Gen[MockStorage] =
    getListGen(0, maxSize, uint256Gen).map(MockStorage.fromSeq)

  val ownerAddr: Address  = Address(0x123456)
  val callerAddr: Address = Address(0xabcdef)

  val exampleBlockContext = ValidBlock.blockContext

  // scalastyle:off
  def getProgramStateGen(
      stackGen: Gen[Stack] = getStackGen(),
      memGen: Gen[Memory] = getMemoryGen(),
      storageGen: Gen[MockStorage] = getStorageGen(),
      gasGen: Gen[BigInt] = getBigIntGen(min = UInt256.MaxValue.toBigInt, max = UInt256.MaxValue.toBigInt),
      codeGen: Gen[ByteString] = getByteStringGen(0, 0),
      inputDataGen: Gen[ByteString] = getByteStringGen(0, 0),
      valueGen: Gen[UInt256] = getUInt256Gen(),
      blockNumberGen: Gen[BlockNumber] = getBlockNumberGen(BlockNumber(0), BlockNumber(300)),
      evmConfigGen: Gen[EvmConfig] = Gen.const(EvmConfig.IstanbulConfigBuilder(blockchainConfig)),
      returnDataGen: Gen[ByteString] = getByteStringGen(0, 0),
      isTopHeader: Boolean = false
  ): Gen[PS] =
    for {
      stack          <- stackGen
      memory         <- memGen
      storage        <- storageGen
      gas            <- gasGen
      code           <- codeGen
      inputData      <- inputDataGen
      value          <- valueGen
      blockNumber    <- blockNumberGen
      blockPlacement <- getBigIntGen(0, blockNumber.value).map(BlocksDistance(_))
      returnData     <- returnDataGen
      evmConfig      <- evmConfigGen

      blockContext = exampleBlockContext.copy(number = if (isTopHeader) blockNumber else blockNumber - blockPlacement)

      world = MockWorldState(numberOfHashes = blockNumber.previous.toUInt256)
                .saveCode(ownerAddr, code)
                .saveStorage(ownerAddr, storage)
                .saveAccount(ownerAddr, Account.empty().increaseBalance(value))

      context: PC = ProgramContext(
                      callerAddr = callerAddr,
                      originAddr = callerAddr,
                      recipientAddr = Some(ownerAddr),
                      maxPriorityFeePerGas = 0,
                      startGas = gas,
                      intrinsicGas = BigInt(0),
                      inputData = inputData,
                      value = value,
                      endowment = value,
                      blockContext = blockContext,
                      doTransfer = true,
                      callDepth = 0,
                      world = world,
                      initialAddressesToDelete = Set(),
                      evmConfig = evmConfig,
                      originalWorld = world,
                      warmAddresses = Set(callerAddr, ownerAddr),
                      warmStorage = Set.empty
                    )

      env = ExecEnv(context, code, ownerAddr)

      vm = EVM[MockWorldState, MockStorage]()

    } yield ProgramState(vm.callWithTrace, vm.createWithTrace, vm.selfDestruct, context, env)
      .withStack(stack)
      .withMemory(memory)
      .withReturnData(returnData)

}
