package io.iohk.scevm.exec.vm

import io.iohk.bytes.ByteString
import io.iohk.bytes.ByteString.{empty => bEmpty}
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.config.EthCompatibilityConfig
import io.iohk.scevm.domain._
import io.iohk.scevm.exec.config.{BlockchainConfigForEvm, EvmConfig}
import io.iohk.scevm.exec.vm.MockWorldState._
import io.iohk.scevm.exec.vm.tracing.JSTracer
import io.iohk.scevm.exec.vm.tracing.Tracer.TracingError
import io.iohk.scevm.testing.fixtures._
import io.iohk.scevm.testing.{IOSupport, NormalPatience}
import io.iohk.scevm.utils.SystemTime.UnixTimestamp.LongToUnixTimestamp
import org.json4s.native.JsonMethods.parse
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.duration.Duration

class VMSpec
    extends AnyWordSpec
    with ScalaCheckPropertyChecks
    with Matchers
    with ScalaFutures
    with IOSupport
    with NormalPatience {

  "VM" when {
    "executing message call" should {
      "only transfer if recipient's account has no code" in new MessageCall {

        val context: PC                                        = getContext()
        val result: ProgramResult[MockWorldState, MockStorage] = vm.run(context).ioValue

        result.world.getBalance(recipientAddr.get) shouldEqual context.value
      }

      "execute recipient's contract" in new MessageCall {
        val inputData: ByteString = UInt256(42).bytes

        // store first 32 bytes of input data as value at offset 0
        val code: ByteString = Assembly(PUSH1, 0, CALLDATALOAD, PUSH1, 0, SSTORE).code

        val world: MockWorldState = defaultWorld.saveCode(recipientAddr.get, code)

        val context: PC = getContext(world = world, inputData = inputData)

        val result: ProgramResult[MockWorldState, MockStorage] = vm.run(context).ioValue

        result.world.getBalance(recipientAddr.get) shouldEqual context.value
        result.world.getStorage(recipientAddr.get).load(0) shouldEqual 42
      }
    }

    "executing contract creation" should {
      "create new contract" in new ContractCreation {
        val context1: PC                                        = getContext()
        val result1: ProgramResult[MockWorldState, MockStorage] = vm.run(context1).ioValue

        result1.world.getCode(expectedNewAddress) shouldEqual defaultContractCode
        result1.world.getBalance(expectedNewAddress) shouldEqual context1.value
        result1.world.getStorage(expectedNewAddress).load(storageOffset) shouldEqual storedValue

        val context2: PC =
          getContext(value = UInt256.Zero, Some(expectedNewAddress), result1.world, bEmpty, homesteadConfig)
        val result2: ProgramResult[MockWorldState, MockStorage] = vm.run(context2).ioValue

        result2.world.getStorage(expectedNewAddress).load(storageOffset) shouldEqual secondStoredValue
      }

      "go OOG if new contract's code size exceeds limit and block is after eip161" in new ContractCreation {
        val codeSize: Int            = evmBlockchainConfig.maxCodeSize.get.toInt + 1
        val contractCode: ByteString = ByteString(Array.fill(codeSize)(-1.toByte))

        val context: PC = getContext(
          inputData = initCode(contractCode),
          evmConfig = homesteadConfig.copy(blockchainConfig =
            homesteadConfig.blockchainConfig.copy(spuriousDragonBlockNumber = BlockNumber(1))
          )
        )
        val result: ProgramResult[MockWorldState, MockStorage] = vm.run(context).ioValue

        result.error shouldBe Some(OutOfGas)
      }

      "fail to create contract in case of address conflict (non-empty code)" in new ContractCreation {
        val nonEmptyCodeHash: ByteString = ByteString(1)
        val world: MockWorldState        = defaultWorld.saveAccount(expectedNewAddress, Account(codeHash = nonEmptyCodeHash))

        val context: PC                                        = getContext(world = world)
        val result: ProgramResult[MockWorldState, MockStorage] = vm.run(context).ioValue

        result.error shouldBe Some(InvalidOpCode(INVALID.code))
      }

      "fail to create contract in case of address conflict (non-zero nonce)" in new ContractCreation {
        val world: MockWorldState = defaultWorld.saveAccount(expectedNewAddress, Account(nonce = Nonce(1)))

        val context: PC                                        = getContext(world = world)
        val result: ProgramResult[MockWorldState, MockStorage] = vm.run(context).ioValue

        result.error shouldBe Some(InvalidOpCode(INVALID.code))
      }

      "create contract if the account already has some balance, but zero nonce and empty code" in new ContractCreation {
        val world: MockWorldState = defaultWorld.saveAccount(expectedNewAddress, Account(balance = 1))

        val context: PC                                        = getContext(world = world)
        val result: ProgramResult[MockWorldState, MockStorage] = vm.run(context).ioValue

        result.error shouldBe None
        result.world.getBalance(expectedNewAddress) shouldEqual context.value + 1
        result.world.getCode(expectedNewAddress) shouldEqual defaultContractCode
      }

      "initialise a new contract account with zero nonce before EIP-161" in new ContractCreation {
        val context: PC                                        = getContext(evmConfig = homesteadConfig)
        val result: ProgramResult[MockWorldState, MockStorage] = vm.run(context).ioValue

        result.world.getAccount(expectedNewAddress).map(_.nonce) shouldEqual Some(Nonce.Zero)
      }

      "initialise a new contract account with incremented nonce after EIP-161" in new ContractCreation {
        val world: MockWorldState = defaultWorld.copy(noEmptyAccountsCond = true)

        val context: PC                                        = getContext(world = world, evmConfig = eip161Config)
        val result: ProgramResult[MockWorldState, MockStorage] = vm.run(context).ioValue

        result.world.getAccount(expectedNewAddress).map(_.nonce) shouldEqual Some(Nonce(1))
      }
    }

    "running with tracing" should {
      "return tx execution time" in new MessageCallWithTracingOpcodes {
        val js: String =
          """
             {
                 opcodes: [],
                 step: function(log) {},
                 fault: function() {},
                 result: function(ctx) { return ctx.time; }
             }
             """
        // ctx.time returns something like "40 nanoseconds"
        val timeRegex     = """"(\d)+ nanoseconds""""
        val executionRime = runEvmTraceWithJs(js, context)
        executionRime.map(_.matches(timeRegex)) shouldBe Right(true)
      }

      "return tx execution time of Zero when tracing fails" in new MessageCallWithTracingOpcodes {
        val badJS: String =
          """
             {
                 opcodes: [],
                 step: function(log)
                 fault: function()
                 result: function(ctx) { return ctx.time; }
             }
             """
        val tracer        = new JSTracer(badJS)
        val evmTracing    = EVM[MockWorldState, MockStorage](tracer)
        val executionRime = evmTracing.run(context).ioValue
        executionRime.executionTime shouldBe Duration.Zero
      }

      // this next array of test was imported from Geth and mentions their original description
      // https://github.com/ethereum/go-ethereum/blob/master/eth/tracers/js/tracer_test.go#L103-L141
      // The tests run the same list of opcodes with different JS traces
      "handle `to-string of opcodes`" in new MessageCallWithTracingOpcodes {
        val js: String =
          """
             {
                 opcodes: [],
                 step: function(log) { this.opcodes.push(log.op.toString()); },
                 fault: function() {},
                 result: function() { return this.opcodes; }
             }
             """
        val resultOrError = runEvmTraceWithJs(js, context)
        resultOrError shouldBe Right("""["PUSH1","PUSH1","STOP"]""")
      }

      "handle `that we don't panic on bad arguments to memory access`" in new MessageCallWithTracingOpcodes {
        val js =
          """
             {
                depths: [],
                step: function(log) { this.depths.push(log.memory.slice(-1,-2)); },
                fault: function() {},
                result: function() { return this.depths; }
              }
             """

        val resultOrError = runEvmTraceWithJs(js, context)
        resultOrError match {
          case Right(result) => fail(s"Expected memory out of bound error, got ${parse(new String(result))}")
          case Left(ex) =>
            ex.toString.contains(
              "Wrapped Out of memory bounds error: Memory.slice function was invoked with parameters start: -1 and stop: -2"
            ) shouldBe true
        }
      }

      "handle `that we don't panic on bad arguments to stack peeks`" in new MessageCallWithTracingOpcodes {
        val js =
          """
             {
               depths: [],
               step: function(log) { this.depths.push(log.stack.peek(-1)); },
               fault: function() {},
               result: function() { return this.depths; }
             }
             """

        val resultOrError = runEvmTraceWithJs(js, context)
        resultOrError match {
          case Right(result) => fail(s"Expected out of bound stack error, got ${parse(new String(result))}")
          case Left(ex) =>
            ex.toString.contains(
              "Wrapped Out of stack bounds error: Stack.peek function was invoked with parameter index: -1"
            ) shouldBe true
        }
      }

      "handle `tests that we don't panic on bad arguments to memory getUint`" in new MessageCallWithTracingOpcodes {
        val js =
          """
             {
               depths: [],
               step: function(log, db) { this.depths.push(log.memory.getUint(-64));},
               fault: function() {},
               result: function() { return this.depths; }
             }
             """

        val resultOrError = runEvmTraceWithJs(js, context)
        resultOrError match {
          case Right(result) => fail(s"Expected out of bound stack error, got ${parse(new String(result))}")
          case Left(ex) =>
            ex.toString.contains(
              "Wrapped Out of memory bounds error: Memory.getUint function was invoked with parameter offset: -64"
            ) shouldBe true
        }
      }

      "handle `tests some general counting`" in new MessageCallWithTracingOpcodes {
        val js =
          """
             {
               count: 0,
               step: function() { this.count += 1; },
               fault: function() {},
               result: function() { return this.count; }
             }
             """

        val resultOrError = runEvmTraceWithJs(js, context)
        resultOrError shouldBe Right("3.0")
      }

      "handle `tests that depth is reported correctly`" in new MessageCallWithTracingOpcodes {
        val js =
          """
             {
               depths: [],
               step: function(log) { this.depths.push(log.stack.length()); },
               fault: function() {},
               result: function() { return this.depths; }
             }
             """

        val resultOrError = runEvmTraceWithJs(js, context)
        resultOrError shouldBe Right("[0,1,2]")
      }

      "handle `tests memory length`" in new MessageCallWithTracingOpcodes {
        val js =
          """
             {
               lengths: [],
               step: function(log) { this.lengths.push(log.memory.length()); },
               fault: function() {},
               result: function() { return this.lengths; }
             }
             """

        val resultOrError = runEvmTraceWithJs(js, context)
        resultOrError shouldBe Right("[0,0,0]")
      }

      "handle `tests intrinsic gas`" in new MessageCallWithTracingOpcodes {
        val js =
          """
             {
               depths: [],
               step: function() {},
               fault: function() {},
               result: function(ctx) { return ctx.gasPrice+'.'+ctx.gasUsed+'.'+ctx.intrinsicGas; }
             }
             """

        val resultOrError = runEvmTraceWithJs(js, context)
        resultOrError shouldBe Right(""""100000.6.21000"""")
      }

      "handle isPrecompiled() for non precompiled contracts" in new MessageCallWithTracingOpcodes {
        val js =
          """
             {
               res: null,
               step: function(log) {},
               fault: function() {},
               result: function() { return Tracer.isPrecompiled('0x99255555555555555555'); }
             }
             """

        val resultOrError = runEvmTraceWithJs(js, context)
        resultOrError shouldBe Right("""false""")
      }

      "handle BigInt by converting an hex string to a BigInt" in new MessageCallWithTracingOpcodes {
        val js =
          """
             {
               res: null,
               step: function(log) {},
               fault: function() {},
               result: function() { return [Tracer.bigInt('0xFF'), Tracer.bigInt('0x00'), Tracer.bigInt('0x10000')]; }
             }
             """

        val resultOrError = runEvmTraceWithJs(js, context)
        resultOrError shouldBe Right("""[255,0,65536]""")
      }

      "handle isPrecompiled() for precompiled sha256 contract" in new MessageCallWithTracingOpcodes {
        val sha256Addr = Address(2).toString()
        val js =
          s"""
             {
               res: null,
               step: function(log) {},
               fault: function() {},
               result: function() { return Tracer.isPrecompiled('$sha256Addr'); }
             }
             """

        val resultOrError = runEvmTraceWithJs(js, context)
        resultOrError shouldBe Right("""true""")
      }

      // the following array of tests were also ported from Geth
      // https://github.com/ethereum/go-ethereum/blob/master/core/vm/runtime/runtime_test.go#L685-L858
      // The tests run the same JS trace but with different lists of opcodes
      "handle `CREATE`" in new MessageCallWithTracingJs {
        val code: ByteString =
          Assembly(PUSH5, PUSH1, 0, PUSH1, 0, RETURN, PUSH1, 0, MSTORE, PUSH1, 5, PUSH1, 27, PUSH1, 0, CREATE, POP).code

        override val recipientAddr: Option[Address] = Some(Address.apply("0xaa"))

        val updatedWorld: MockWorldState = world.saveCode(recipientAddr.get, code)
        val context: MockWorldState.PC   = getContext(world = updatedWorld, inputData = inputData)

        val resultOrError1 = runEvmTraceWithJs(js1, context)
        resultOrError1 shouldBe Right(""""1,1,952855,6,12"""")

        val resultOrError2 = runEvmTraceWithJs(js2, context)
        resultOrError2 shouldBe Right(""""1,1,952855,6,0"""")
      }

      "handle `CREATE2`" in new MessageCallWithTracingJs {
        val code: ByteString =
          Assembly(
            PUSH5,
            PUSH1,
            0,
            PUSH1,
            0,
            RETURN,
            PUSH1,
            0,
            MSTORE,
            PUSH1,
            1,
            PUSH1,
            5,
            PUSH1,
            27,
            PUSH1,
            0,
            CREATE2,
            POP
          ).code

        override val recipientAddr = Some(Address.apply("0xaa"))

        val updatedWorld: MockWorldState = world.saveCode(recipientAddr.get, code)
        val context: MockWorldState.PC   = getContext(world = updatedWorld, inputData = inputData)

        val resultOrError1 = runEvmTraceWithJs(js1, context)
        resultOrError1 shouldBe Right(""""1,1,952846,6,13"""")

        val resultOrError2 = runEvmTraceWithJs(js2, context)
        resultOrError2 shouldBe Right(""""1,1,952846,6,0"""")
      }

      "handle `CALL`" in new MessageCallWithTracingJs {
        val code: ByteString =
          Assembly(PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0xbb, GAS, CALL, POP).code

        override val recipientAddr = Some(Address.apply("0xaa"))

        val updatedWorld: MockWorldState = world.saveCode(recipientAddr.get, code)
        val context: MockWorldState.PC   = getContext(world = updatedWorld, inputData = inputData)

        val resultOrError1 = runEvmTraceWithJs(js1, context)
        resultOrError1 shouldBe Right(""""1,1,983667,6,13"""") // expected in Geth "1,1,981796,6,13"

        val resultOrError2 = runEvmTraceWithJs(js2, context)
        resultOrError2 shouldBe Right(""""1,1,983667,6,0"""") // expected in Geth "1,1,981796,6,0"
      }

      "handle `CALLCODE`" in new MessageCallWithTracingJs {
        val code: ByteString =
          Assembly(PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0xcc, GAS, CALLCODE, POP).code

        override val recipientAddr = Some(Address.apply("0xaa"))

        val updatedWorld: MockWorldState = world.saveCode(recipientAddr.get, code)
        val context: MockWorldState.PC   = getContext(world = updatedWorld, inputData = inputData)

        val resultOrError1 = runEvmTraceWithJs(js1, context)
        resultOrError1 shouldBe Right(""""1,1,983667,6,13"""") // expected in Geth "1,1,981796,6,13"

        val resultOrError2 = runEvmTraceWithJs(js2, context)
        resultOrError2 shouldBe Right(""""1,1,983667,6,0"""") // expected in Geth "1,1,981796,6,0"
      }

      "handle `STATICCALL`" in new MessageCallWithTracingJs {
        val code: ByteString =
          Assembly(PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0xdd, GAS, STATICCALL, POP).code

        override val recipientAddr = Some(Address.apply("0xaa"))

        val updatedWorld: MockWorldState = world.saveCode(recipientAddr.get, code)
        val context: MockWorldState.PC   = getContext(world = updatedWorld, inputData = inputData)

        val resultOrError1 = runEvmTraceWithJs(js1, context)
        resultOrError1 shouldBe Right(""""1,1,983670,6,12"""") // expected in Geth "1,1,981799,6,12"

        val resultOrError2 = runEvmTraceWithJs(js2, context)
        resultOrError2 shouldBe Right(""""1,1,983670,6,0"""") // expected in Geth "1,1,981799,6,0"
      }

      "handle `DELEGATECALL`" in new MessageCallWithTracingJs {
        val code: ByteString =
          Assembly(PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0xee, GAS, DELEGATECALL, POP).code

        override val recipientAddr = Some(Address.apply("0xaa"))

        val updatedWorld: MockWorldState = world.saveCode(recipientAddr.get, code)
        val context: MockWorldState.PC   = getContext(world = updatedWorld, inputData = inputData)

        val resultOrError1 = runEvmTraceWithJs(js1, context)
        resultOrError1 shouldBe Right(""""1,1,983670,6,12"""") // expected in Geth "1,1,981799,6,12"

        val resultOrError2 = runEvmTraceWithJs(js2, context)
        resultOrError2 shouldBe Right(""""1,1,983670,6,0"""") // expected in Geth "1,1,981799,6,0"
      }

      "handle `CALL self-destructing contract`" in new MessageCallWithTracingJs {
        val code: ByteString =
          Assembly(PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0, PUSH1, 0xff, GAS, CALL, POP).code

        override val recipientAddr = Some(Address.apply("0xaa"))

        val updatedWorld: MockWorldState = world.saveCode(recipientAddr.get, code)
        val context: MockWorldState.PC   = getContext(world = updatedWorld, inputData = inputData)

        val resultOrError1 = runEvmTraceWithJs(js1, context)
        resultOrError1 shouldBe Right(""""2,2,0,5003,12"""")

        val resultOrError2 = runEvmTraceWithJs(js2, context)
        resultOrError2 shouldBe Right(""""2,2,0,5003,0"""")
      }

      // From Geth:
      // https://github.com/ethereum/go-ethereum/blob/master/core/vm/runtime/runtime_test.go#L860-L891
      "handle `create tx`" in new TestSetup {
        override val recipientAddr: Option[Address] = Some(Address(0xdeadbeefL))

        def getContext(world: MockWorldState = defaultWorld, inputData: ByteString = bEmpty): PC =
          getContext(value = UInt256.Zero, recipientAddr, world, inputData, berlinConfig)

        val inputData: ByteString = UInt256(42).bytes
        val js =
          """
       {
         enters: 0, exits: 0,
         step: function() {},
         fault: function() {},
         result: function() { return [this.enters, this.exits].join(",") },
         enter: function(frame) { this.enters++ },
         exit: function(res) { this.exits++ }
       }
       """
        val world: MockWorldState = defaultWorld

        val code: ByteString = Assembly(PUSH1, 0, PUSH1, 0, RETURN).code

        val updatedWorld: MockWorldState = world.saveCode(recipientAddr.get, code)
        val context: MockWorldState.PC   = getContext(world = updatedWorld, inputData = inputData)

        val resultOrError1 = runEvmTraceWithJs(js, context)
        resultOrError1 shouldBe Right(""""0,0"""")
      }
    }

    def runEvmTraceWithJs(js: String, context: MockWorldState.PC): Either[TracingError, String] = {
      val tracer     = new JSTracer(js)
      val evmTracing = EVM[MockWorldState, MockStorage](tracer)
      val result     = evmTracing.run(context).ioValue

      tracer.result(context, result)
    }
  }

  trait TestSetup {
    val vm: EVM[MockWorldState, MockStorage] = EVM[MockWorldState, MockStorage]()

    val blockContext: BlockContext = ValidBlock.blockContext.copy(
      number = BlockNumber(1),
      gasLimit = 10000000,
      unixTimestamp = 0L.millisToTs
    )

    private val blockNumberMaxLong = BlockNumber(Long.MaxValue)
    val evmBlockchainConfig: BlockchainConfigForEvm = BlockchainConfigForEvm(
      frontierBlockNumber = blockNumberMaxLong,
      homesteadBlockNumber = blockNumberMaxLong,
      eip150BlockNumber = blockNumberMaxLong,
      spuriousDragonBlockNumber = blockNumberMaxLong,
      byzantiumBlockNumber = blockNumberMaxLong,
      constantinopleBlockNumber = blockNumberMaxLong,
      petersburgBlockNumber = blockNumberMaxLong,
      istanbulBlockNumber = blockNumberMaxLong,
      berlinBlockNumber = blockNumberMaxLong,
      londonBlockNumber = blockNumberMaxLong,
      maxCodeSize = Some(16),
      accountStartNonce = Nonce.Zero,
      chainId = ChainId(0x3d),
      ethCompatibility = EthCompatibilityConfig(
        difficulty = 1,
        blockBaseFee = 0
      )
    )

    val homesteadConfig: EvmConfig =
      EvmConfig.forBlock(BlockNumber(0), evmBlockchainConfig.copy(homesteadBlockNumber = BlockNumber(0)))
    val eip161Config: EvmConfig =
      EvmConfig.forBlock(BlockNumber(0), evmBlockchainConfig.copy(spuriousDragonBlockNumber = BlockNumber(0)))

    val berlinConfig: EvmConfig =
      EvmConfig.forBlock(BlockNumber(0), evmBlockchainConfig.copy(berlinBlockNumber = BlockNumber(0)))

    val senderAddr: Address          = Address(0xcafebabeL)
    val senderAcc: Account           = Account(nonce = Nonce(1), balance = 1000000)
    def defaultWorld: MockWorldState = MockWorldState().saveAccount(senderAddr, senderAcc)

    def getContext(
        value: UInt256,
        recipientAddr: Option[Address],
        world: MockWorldState,
        inputData: ByteString,
        evmConfig: EvmConfig
    ): PC =
      ProgramContext(
        callerAddr = senderAddr,
        originAddr = senderAddr,
        recipientAddr = recipientAddr,
        maxPriorityFeePerGas = 100000,
        startGas = 1000000,
        intrinsicGas = BigInt(21000),
        inputData = inputData,
        value = value,
        endowment = value,
        doTransfer = true,
        blockContext = blockContext,
        callDepth = 0,
        world = world,
        initialAddressesToDelete = Set(),
        evmConfig = evmConfig,
        originalWorld = world,
        warmAddresses = Set.empty,
        warmStorage = Set.empty
      )

    def recipientAddr: Option[Address]
  }

  trait MessageCall extends TestSetup {
    override val recipientAddr: Option[Address] = Some(Address(0xdeadbeefL))
    val recipientAcc: Account                   = Account(nonce = Nonce(1))

    override val defaultWorld: MockWorldState = super.defaultWorld.saveAccount(recipientAddr.get, recipientAcc)

    def getContext(world: MockWorldState = defaultWorld, inputData: ByteString = bEmpty): PC =
      getContext(value = 100, recipientAddr, world, inputData, homesteadConfig)
  }

  trait MessageCallWithTracingOpcodes extends MessageCall {
    val inputData: ByteString = UInt256(42).bytes

    val code: ByteString           = Assembly(PUSH1, 1, PUSH1, 1, STOP).code
    val world: MockWorldState      = defaultWorld.saveCode(recipientAddr.get, code)
    val context: MockWorldState.PC = getContext(world = world, inputData = inputData)
  }

  trait MessageCallWithTracingJs extends TestSetup {
    def getContext(world: MockWorldState = defaultWorld, inputData: ByteString = bEmpty): PC =
      getContext(value = UInt256.Zero, recipientAddr, world, inputData, berlinConfig)

    val inputData: ByteString = UInt256(42).bytes
    val js1 =
      """
       {
          enters: 0, exits: 0, enterGas: 0, gasUsed: 0, steps:0,
          step: function() { this.steps++},
          fault: function() {},
          result: function() {
            return [this.enters, this.exits,this.enterGas,this.gasUsed, this.steps].join(",")
          },
          enter: function(frame) {
            this.enters++;
            this.enterGas = frame.getGas();
          },
          exit: function(res) {
            this.exits++;
            this.gasUsed = res.getGasUsed();
          }
        }
       """

    val js2 = """
             {
                enters: 0, exits: 0, enterGas: 0, gasUsed: 0, steps:0,
                fault: function() {},
                result: function() {
                  return [this.enters, this.exits,this.enterGas,this.gasUsed, this.steps].join(",")
                },
                enter: function(frame) {
                  this.enters++;
                  this.enterGas = frame.getGas();
                },
                exit: function(res) {
                  this.exits++;
                  this.gasUsed = res.getGasUsed();
                }
            }"""

    val calleeCode: ByteString    = Assembly(PUSH1, 0, PUSH1, 0, RETURN).code
    val depressedCode: ByteString = Assembly(PUSH1, 0xaa, SELFDESTRUCT).code

    val world: MockWorldState = defaultWorld
      .saveCode(Address.apply("0xbb"), calleeCode)
      .saveCode(Address.apply("0xcc"), calleeCode)
      .saveCode(Address.apply("0xdd"), calleeCode)
      .saveCode(Address.apply("0xee"), calleeCode)
      .saveCode(Address.apply("0xff"), depressedCode)
  }

  trait ContractCreation extends TestSetup {
    val recipientAddr = None

    val expectedNewAddress: Address = defaultWorld.createAddress(senderAddr)

    val storedValue       = 42
    val secondStoredValue = 13
    val storageOffset     = 0

    val defaultContractCode: ByteString =
      Assembly(
        PUSH1,
        secondStoredValue,
        PUSH1,
        storageOffset,
        SSTORE
      ).code

    def initCode(contractCode: ByteString = defaultContractCode): ByteString =
      Assembly(
        PUSH1,
        storedValue,
        PUSH1,
        storageOffset,
        SSTORE, //store an arbitrary value
        PUSH1,
        contractCode.size,
        DUP1,
        PUSH1,
        16,
        PUSH1,
        0,
        CODECOPY,
        PUSH1,
        0,
        RETURN
      ).code ++ contractCode

    def getContext(
        world: MockWorldState = defaultWorld,
        inputData: ByteString = initCode(),
        evmConfig: EvmConfig = homesteadConfig
    ): PC =
      getContext(value = 100, None, world, inputData, evmConfig)
  }

}
