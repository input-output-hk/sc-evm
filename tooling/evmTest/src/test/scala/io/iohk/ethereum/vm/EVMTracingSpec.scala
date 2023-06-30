package io.iohk.ethereum.vm

import io.iohk.bytes.ByteString
import io.iohk.ethereum.vm.utils.EvmTestEnv
import io.iohk.scevm.domain.{Address, BlockContext, UInt256}
import io.iohk.scevm.exec.config.EvmConfig
import io.iohk.scevm.exec.vm._
import io.iohk.scevm.exec.vm.fixtures.blockchainConfig
import io.iohk.scevm.exec.vm.tracing.JSTracer
import io.iohk.scevm.testing.NormalPatience
import io.iohk.scevm.testing.fixtures.ValidBlock
import org.json4s.native.JsonMethods._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class EVMTracingSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks {
  "EVM with tracing" when {
    "correctly handle result function with real contract" in new EvmTestEnv with EvmTracing with NormalPatience {
      val (_, callee) = deployContract("Callee", value = 10, creatorAddress = Address(42))
      val (_, caller) = deployContract("Caller", creatorAddress = Address(43))

      val js =
        s"""
              {
                data: [],
                fault: function(log, db) {},
                step: function(log, db) {},
                result: function(ctx, db) {
                  return {
                    "ctx": {
                      "type": ctx.type,
                      "from": ctx.from,
                      "to": ctx.to,
                      "input": ctx.input,
                      "gas": ctx.gas,
                      "value": ctx.value,
                      "block": ctx.block,
                      "output": ctx.output,
                      "gasUsed": ctx.gasUsed
                    },
                    "db": {
                      "balance": db.getBalance("${callee.address.toString}"),
                      "nonce": db.getNonce("${callee.address.toString}"),
                      "code": db.getCode("${callee.address.toString}"),
                      "state": db.getState("${callee.address.toString}", "0x0"),
                      "exists": db.exists("${callee.address.toString}")
                    }
                  };
                }
              }
              """

      val evmTracing = EVM[MockWorldState, MockStorage](tracer)
      val (programContext, programResult) =
        caller.makeACall(callee.address, ByteString(123)).callWithCustomMV(evmTracing, sender = callerAddr)

      val resultOrError = tracer.result(programContext, programResult)

      resultOrError.map(parse(_)) shouldBe Right(parse("""
          |{
          |  "ctx":{
          |    "output":[],
          |    "input":[63,91,-55,-74,0,0,0,0,0,0,0,0,0,0,0,0,95,78,76,-49,-16,-94,85,59,43,-34,48,-31,-4,-123,49,-78,-121,-37,-112,-121,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,123],
          |    "gasUsed":3468,
          |    "gas":-22873,
          |    "from":"0x0000000000000000000000000000000000000001",
          |    "block":3125369,
          |    "to":"0x644ae75c671b05602bb2692c7d10b9d34559a30a",
          |    "type":"CALL",
          |    "value":0
          |  },
          |  "db":{
          |    "code":[],
          |    "balance":10,
          |    "exists":true,
          |    "state":0,
          |    "nonce":0
          |  }
          |}
          |""".stripMargin))
    }

    "correctly handle db.getState with real contract" in new EvmTestEnv with EvmTracing with NormalPatience {
      val (_, callee) = deployContract("Callee", creatorAddress = Address(42))

      val js =
        s"""
              {
                data: [],
                fault: function(log, db) {},
                step: function(log, db) {},
                result: function(ctx, db) {
                  return {
                    "db": {
                      "state": db.getState("${callee.address.toString}", "0x0")
                    }
                  };
                }
              }
              """

      val evmTracing                      = EVM[MockWorldState, MockStorage](tracer)
      val (programContext, programResult) = callee.setFoo(3).callWithCustomMV(evmTracing, sender = callerAddr)
      val resultOrError1                  = tracer.result(programContext, programResult)
      resultOrError1.map(parse(_)) shouldBe Right(parse("""
       |{
       |  "db":{
       |    "state":3
       |  }
       |}
       |""".stripMargin))

      val (programContext2, programResult2) =
        callee.setFoo(4).callWithCustomMV(evmTracing, sender = callerAddr)
      val resultOrError2 = tracer.result(programContext2, programResult2)
      resultOrError2.map(parse(_)) shouldBe Right(parse("""
       |{
       |  "db":{
       |    "state":4
       |  }
       |}
       |""".stripMargin))
    }

    "correctly handle BigInt" in new EvmTestEnv with EvmTracing {
      val (_, callee) = deployContract("Callee", creatorAddress = Address(42), value = BigInt(2).pow(256) - 1)

      val js =
        s"""
              {
                data: [],
                fault: function(log, db) {},
                step: function(log, db) {},
                result: function(ctx, db) {
                  return {
                    "db": {
                      "balance": db.getBalance("${callee.address.toString}"),
                    }
                  };
                }
              }
              """

      val evmTracing                      = EVM[MockWorldState, MockStorage](tracer)
      val (programContext, programResult) = callee.setFoo(3).callWithCustomMV(evmTracing, sender = callerAddr)
      val resultOrError1                  = tracer.result(programContext, programResult)
      resultOrError1.map(parse(_)) shouldBe Right(parse("""
        |{
        |  "db":{
        |    "balance": 115792089237316195423570985008687907853269984665640564039457584007913129639935
        |  }
        |}
        |""".stripMargin))
    }

    "correctly handle step function with real contract" in new EvmTestEnv with EvmTracing with NormalPatience {
      val (_, callee) = deployContract("Callee", value = 10, creatorAddress = Address(42))
      val (_, caller) = deployContract("Caller", creatorAddress = Address(43))

      val js =
        s"""
              {
                data: [],
                fault: function(log, db) {},
                step: function(log, db) {
                  if (this.data.length === 0) {
                    // Record only the state for the first opcode
                    this.data.push(
                      {
                        "log": {
                          "stack": {
                            "peek": log.stack.peek(0),
                            "length": log.stack.length()
                          },
                          "op": {
                            "isPush": log.op.isPush(),
                            "toString": log.op.toString(),
                            "toNumber": log.op.toNumber(),
                          },
                          "memory": {
                            "slice": log.memory.slice(0, 1),
                            "uint": log.memory.getUint("0")
                          },
                          "contract": {
                            "caller": log.contract.getCaller(),
                            "address": log.contract.getAddress(),
                            "value": log.contract.getValue(),
                            "input": log.contract.getInput()
                          },
                          "PC": log.getPC(),
                          "gas": log.getGas(),
                          "cost": log.getCost(),
                          "depth": log.getDepth(),
                          "refund": log.getRefund(),
                          "error": log.getError()
                        },
                        "db": {
                          "balance": db.getBalance("${callee.address.toString}"),
                          "nonce": db.getNonce("${callee.address.toString}"),
                          "code": db.getCode("${callee.address.toString}"),
                          "state": db.getState("${callee.address.toString}", "0"),
                          "exists": db.exists("${callee.address.toString}")
                        }
                      }
                    );
                  }
                },
                result: function(ctx, db) {
                  return this.data;
                }
              }
              """

      val evmTracing = EVM[MockWorldState, MockStorage](tracer)
      val (programContext, programResult) = caller
        .makeACall(callee.address, ByteString(123))
        .callWithCustomMV(evmTracing, sender = callerAddr, gasLimit = BigInt(30000))

      val resultOrError = tracer.result(programContext, programResult)
      resultOrError.map(parse(_)) shouldBe Right(parse("""
      |[
      |  {
      |    "log": {
      |      "op": {
      |        "isPush": true,
      |        "toString": "PUSH1",
      |        "toNumber": 96,
      |      },
      |      "stack": {
      |        "length": 0,
      |        "peek": 0
      |      },
      |      "memory": {
      |        "slice": [0],
      |        "uint": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]
      |      }
      |      "contract": {
      |        "input": [63,91,-55,-74,0,0,0,0,0,0,0,0,0,0,0,0,95,78,76,-49,-16,-94,85,59,43,-34,48,-31,-4,-123,49,-78,-121,-37,-112,-121,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,123],
      |        "caller": "0x0000000000000000000000000000000000000001",
      |        "address": "0x644ae75c671b05602bb2692c7d10b9d34559a30a",
      |        "value": 0
      |      },
      |      "PC": 0,
      |      "cost": 3,
      |      "depth": 0,
      |      "gas": 7128,
      |      "error": null,
      |      "refund": 0
      |    },
      |    "db": {
      |      "code": [],
      |      "balance": 10,
      |      "exists": true,
      |      "state": 0,
      |      "nonce": 0
      |    }
      |  }
      |]
      |""".stripMargin))
    }

    "correctly handle memory in step function with real contract" in new EvmTestEnv
      with EvmTracing
      with NormalPatience {
      val (_, callee) = deployContract("Callee", value = 10, creatorAddress = Address(42))
      val (_, caller) = deployContract("Caller", creatorAddress = Address(43))

      val js =
        s"""
              {
                memoryUpdated: false,
                data: [],
                fault: function(log, db) {},
                step: function(log, db) {
                  if (this.memoryUpdated && this.data.length === 0) {
                    // Record only the first time the memory is not empty
                    this.data.push(
                      {
                        "memory": {
                          "slice": log.memory.slice(0, 96),
                          "slice2": log.memory.slice(95, 96),
                          "uint": log.memory.getUint("95")
                        }
                      }
                    );
                  }
                  if (log.op.toString()== "MSTORE") {
                    // The next opcode should be able to see some data in the memory
                    this.memoryUpdated = true;
                  }
                },
                result: function(ctx, db) {
                  return this.data;
                }
              }
              """

      val evmTracing = EVM[MockWorldState, MockStorage](tracer)
      val (programContext, programResult) = caller
        .makeACall(callee.address, ByteString(123))
        .callWithCustomMV(evmTracing, sender = callerAddr, gasLimit = BigInt(30000))

      val resultOrError = tracer.result(programContext, programResult)
      resultOrError.map(parse(_)) shouldBe Right(parse("""
        |[
        |  {
        |    "memory": {
        |      "slice": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,-128],
        |      "slice2": [-128],
        |      "uint": [-128,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]
        |    }
        |  }
        |]
        |""".stripMargin))
    }

    "correctly handle fault function with real contract" in new EvmTestEnv with EvmTracing {
      val savedAccount: Address = createAccount(10)
      val (_, contract)         = deployContract("Throw", creatorAddress = Address(2))

      val js =
        s"""
              {
                data: [],
                fault: function(log, db) {
                  if (this.data.length === 0) {
                    // Record only the state for the first opcode
                    this.data.push(
                      {
                        "log": {
                          "stack": {
                            "peek": log.stack.peek(0),
                            "length": log.stack.length()
                          },
                          "op": {
                            "isPush": log.op.isPush(),
                            "toString": log.op.toString(),
                            "toNumber": log.op.toNumber(),
                          },
                          "memory": {
                            "slice": log.memory.slice(0, 1),
                            "uint": log.memory.getUint("0")
                          },
                          "contract": {
                            "caller": log.contract.getCaller(),
                            "address": log.contract.getAddress(),
                            "value": log.contract.getValue(),
                            "input": log.contract.getInput()
                          },
                          "PC": log.getPC(),
                          "gas": log.getGas(),
                          "cost": log.getCost(),
                          "depth": log.getDepth(),
                          "refund": log.getRefund(),
                          "error": log.getError()
                        },
                        "db": {
                          "balance": db.getBalance("${savedAccount.toString}"),
                          "nonce": db.getNonce("${savedAccount.toString}"),
                          "code": db.getCode("${savedAccount.toString}"),
                          "state": db.getState("${savedAccount.toString}", "0"),
                          "exists": db.exists("${savedAccount.toString}")
                        }
                      }
                    );
                  }
                },
                step: function(log, db) {},
                result: function(ctx, db) {
                  return this.data;
                }
              }
              """

      val evmTracing = EVM[MockWorldState, MockStorage](tracer)
      val (programContext, programResult) =
        contract.justThrow().callWithCustomMV(evmTracing, gasLimit = BigInt(30000), sender = callerAddr)

      val resultOrError = tracer.result(programContext, programResult)
      resultOrError.map(parse(_)) shouldBe Right(parse("""
        |[
        |  {
        |    "log": {
        |      "op": {
        |        "isPush": false,
        |        "toString": "REVERT",
        |        "toNumber": -3,
        |      },
        |      "stack": {
        |        "length": 3,
        |        "peek": 64
        |      },
        |      "memory": {
        |        "slice": [78],
        |        "uint": [78,72,123,113,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]
        |      }
        |      "contract": {
        |        "input": [-59,7,33,114],
        |        "caller": "0x0000000000000000000000000000000000000001",
        |        "address": "0xe536720791a7dadbebdbcd8c8546fb0791a11901",
        |        "value": 0
        |      },
        |      "PC": 113,
        |      "cost": 3,
        |      "depth": 0,
        |      "gas": 8560,
        |      "error": "RevertOccurs",
        |      "refund": 0
        |    },
        |    "db": {
        |      "code": [],
        |      "balance": 10,
        |      "exists": true,
        |      "state": 0,
        |      "nonce": 0
        |    }
        |  }
        |]
        |""".stripMargin))
    }

  }

  trait EvmTracing { _: EvmTestEnv =>
    protected val ownerAddr: Address        = Address(0x123456)
    protected val callerAddr: Address       = Address(0x1)
    protected val gas: BigInt               = BigInt(1000)
    protected val inputData: ByteString     = ByteString.empty
    protected val blockHeader: BlockContext = ValidBlock.blockContext
    protected val evmConfig: EvmConfig      = EvmConfig.IstanbulConfigBuilder(blockchainConfig)

    protected val context: ProgramContext[MockWorldState, MockStorage] = ProgramContext(
      callerAddr = callerAddr,
      originAddr = callerAddr,
      recipientAddr = Some(ownerAddr),
      maxPriorityFeePerGas = 0,
      startGas = gas,
      intrinsicGas = BigInt(0),
      inputData = inputData,
      value = UInt256(1),
      endowment = UInt256(1),
      blockContext = blockHeader,
      doTransfer = true,
      callDepth = 0,
      world = world,
      initialAddressesToDelete = Set(),
      evmConfig = evmConfig,
      originalWorld = world,
      warmAddresses = Set(callerAddr, ownerAddr),
      warmStorage = Set.empty
    )

    protected val env: ExecEnv = ExecEnv(context, ByteString.empty, ownerAddr)

    protected val state: ProgramState[MockWorldState, MockStorage] =
      ProgramState(callMethod, createMethod, selfDestructMethod, context, env)

    protected val js: String
    protected lazy val tracer: JSTracer = new JSTracer(js)

    def callMethod[W <: WorldState[W, S], S <: Storage[S]](
        context: ProgramContext[W, S],
        address: Address
    ): ProgramResult[W, S] = ???

    def createMethod[W <: WorldState[W, S], S <: Storage[S]](
        context: ProgramContext[W, S],
        value: Option[UInt256]
    ): (ProgramResult[W, S], Address) = ???

    def selfDestructMethod[W <: WorldState[W, S], S <: Storage[S]](
        context: ProgramContext[W, S],
        state: ProgramState[W, S]
    ): ProgramState[W, S] = ???
  }
}
