package io.iohk.scevm.exec.vm.tracing

import io.iohk.bytes.ByteString
import io.iohk.scevm.domain.{Address, BlockContext, UInt256}
import io.iohk.scevm.exec.config.EvmConfig
import io.iohk.scevm.exec.vm.fixtures.blockchainConfig
import io.iohk.scevm.exec.vm.tracing.Tracer.TracingException
import io.iohk.scevm.exec.vm.{
  ExecEnv,
  MockStorage,
  MockWorldState,
  OpCode,
  PUSH1,
  ProgramContext,
  ProgramResult,
  ProgramState,
  Storage,
  WorldState
}
import io.iohk.scevm.testing.fixtures.ValidBlock
import org.json4s.Extraction
import org.json4s.reflect.TypeInfo
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class JSTracerSpec extends AnyWordSpec with Matchers with ScalaCheckPropertyChecks {
  "JSTracer" should {
    "correctly handle invalid javascript during initialization" in new JsTraceEnv {
      val js: String       = "invalid javascript code"
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe false
    }

    "correctly handle missing fault function" in new JsTraceEnv {
      val js: String       = "{ result: function(ctx, db) { return []; } }"
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult shouldBe Left(
        TracingException("Javascript-based tracing must define `fault` function.")
      )
    }

    "correctly handle missing result function" in new JsTraceEnv {
      val js: String       = "{ fault: function(log, db) {} }"
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult shouldBe Left(
        TracingException("Javascript-based tracing must define `result` function.")
      )
    }

    "correctly handle missing step or enter and exit functions" in new JsTraceEnv {
      val js: String =
        """{
          |  fault: function(log, db) {},
          |  result: function(ctx, db) { return []; }
          |}""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult shouldBe Left(
        TracingException(
          "Javascript-based tracing must define `step` function or `enter` and `exit` functions (or all)."
        )
      )
    }

    "correctly handle missing enter function when only exit function is defined" in new JsTraceEnv {
      val js: String =
        """{
          |  exit: function(frameResult) {},
          |  fault: function(log, db) {},
          |  result: function(ctx, db) { return []; }
          |}""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult shouldBe Left(
        TracingException(
          "Javascript-based tracing must define both `enter` and `exit` functions (or only `step`)."
        )
      )
    }

    "correctly handle missing exit function when only enter function is defined" in new JsTraceEnv {
      val js: String =
        """{
          |  enter: function(callFrame) {},
          |  fault: function(log, db) {},
          |  result: function(ctx, db) { return []; }
          |}""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult shouldBe Left(
        TracingException(
          "Javascript-based tracing must define both `enter` and `exit` functions (or only `step`)."
        )
      )
    }

    "correctly handle missing exit function when only enter and step functions are defined" in new JsTraceEnv {
      val js: String =
        """{
          |  enter: function(callFrame) {},
          |  step: function(log, db) {},
          |  fault: function(log, db) {},
          |  result: function(ctx, db) { return []; }
          |}""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult shouldBe Left(
        TracingException(
          "Javascript-based tracing must define both `enter` and `exit` functions (or only `step`)."
        )
      )
    }

    "correctly handle missing enter function when only exit and step functions are defined" in new JsTraceEnv {
      val js: String =
        """{
          |  exit: function(frameResult) {},
          |  step: function(log, db) {},
          |  fault: function(log, db) {},
          |  result: function(ctx, db) { return []; }
          |}""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult shouldBe Left(
        TracingException(
          "Javascript-based tracing must define both `enter` and `exit` functions (or only `step`)."
        )
      )
    }

    "accept when only fault, result and step functions are defined" in new JsTraceEnv {
      val js: String =
        """{
          |  step: function(log, db) {},
          |  fault: function(log, db) {},
          |  result: function(ctx, db) { return []; }
          |}""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe true
    }

    "accept when only fault, result and enter and exit functions are defined" in new JsTraceEnv {
      val js: String =
        """{
          |  enter: function(callFrame) {},
          |  exit: function(frameResult) {},
          |  fault: function(log, db) {},
          |  result: function(ctx, db) { return []; }
          |}""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe true
    }

    "correctly handle valid code in step function" in new JsTraceEnv {
      val js: String =
        """{
            |  step: function(log, db) {},
            |  fault: function(log, db) {},
            |  result: function(ctx, db) { return []; }
            |}""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe true

      val opCode: OpCode                  = PUSH1
      val result: Either[Throwable, Unit] = tracer.step(opCode, state)
      result.isRight shouldBe true
    }

    "correctly handle error in step function" in new JsTraceEnv {
      val js: String =
        """{
            |  step: function(log, db) { throw new Error("step threw an error"); },
            |  fault: function(log, db) {},
            |  result: function(ctx, db) { return []; }
            |}""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe true

      val opCode: OpCode                  = PUSH1
      val result: Either[Throwable, Unit] = tracer.step(opCode, state)
      result match {
        case Right(_) => fail("Test should fail with 'Error: step threw an error'")
        case Left(ex) =>
          ex.toString shouldBe "org.mozilla.javascript.JavaScriptException: Error: step threw an error (<tracer>#2)"
      }
    }

    "correctly handle valid code in fault function" in new JsTraceEnv {
      val js: String =
        """{
          |  step: function(log, db) {},
          |  fault: function(log, db) {},
          |  result: function(ctx, db) { return []; }
          |  }""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe true

      val opCode: OpCode                  = PUSH1
      val result: Either[Throwable, Unit] = tracer.fault(opCode, state)
      result.isRight shouldBe true
    }

    "correctly handle error in fault function" in new JsTraceEnv {
      val js: String =
        """{
          |  step: function(log, db) {},
          |  fault: function(log, db) { throw new Error("fault threw an error"); },
          |  result: function(ctx, db) { return []; }
          |  }""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe true

      val opCode: OpCode                  = PUSH1
      val result: Either[Throwable, Unit] = tracer.fault(opCode, state)
      result match {
        case Right(_) => fail("Test should fail with 'Error: fault threw an error'")
        case Left(ex) =>
          ex.toString shouldBe "org.mozilla.javascript.JavaScriptException: Error: fault threw an error (<tracer>#3)"
      }
    }

    "correctly handle valid code in enter function" in new JsTraceEnv {
      val js: String =
        """{
          |  enter: function(callFrame) {},
          |  exit: function(frameResult) {},
          |  fault: function(log, db) {},
          |  result: function(ctx, db) { return []; }
          |}""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe true

      val result: Either[Throwable, Unit] = tracer.enter(context)
      result.isRight shouldBe true
    }

    "correctly handle error in enter function" in new JsTraceEnv {
      val js: String =
        """{
          |  enter: function(callFrame) { throw new Error("enter threw an error"); },
          |  exit: function(frameResult) {},
          |  fault: function(log, db) {},
          |  result: function(ctx, db) { return []; }
          |}""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe true

      val result: Either[Throwable, Unit] = tracer.enter(context)

      result match {
        case Right(_) => fail("Test should fail with 'Error: enter threw an error'")
        case Left(ex) =>
          ex.toString shouldBe "org.mozilla.javascript.JavaScriptException: Error: enter threw an error (<tracer>#2)"
      }
    }

    "correctly handle valid code in exit function" in new JsTraceEnv {
      val js: String =
        """{
          |  enter: function(callFrame) {},
          |  exit: function(frameResult) {},
          |  fault: function(log, db) {},
          |  result: function(ctx, db) { return []; }
          |}""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe true

      val opCode: OpCode                  = PUSH1
      val result: Either[Throwable, Unit] = tracer.exit(context, state.toResult)
      result.isRight shouldBe true
    }

    "correctly handle error in exit function" in new JsTraceEnv {
      val js: String =
        """{
          |  enter: function(callFrame) {},
          |  exit: function(frameResult) { throw new Error("exit threw an error"); },
          |  fault: function(log, db) {},
          |  result: function(ctx, db) { return []; }
          |}""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe true

      val result: Either[Throwable, Unit] = tracer.exit(context, state.toResult)
      result match {
        case Right(_) => fail("Test should fail with 'Error: exit threw an error'")
        case Left(ex) =>
          ex.toString shouldBe "org.mozilla.javascript.JavaScriptException: Error: exit threw an error (<tracer>#3)"
      }
    }

    "correctly handle error in result function" in new JsTraceEnv {
      val js: String =
        """{
          |  step: function(log, db) {},
          |  fault: function(log, db) {},
          |  result: function(ctx, db) { throw new Error("result threw an error"); }
          |  }""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe true
      val result = tracer.result(context, state.toResult)
      result match {
        case Right(_) => fail("Test should fail with Error")
        case Left(ex) =>
          ex.toString shouldBe "org.mozilla.javascript.JavaScriptException: Error: result threw an error (<tracer>#4)"
      }
    }

    // test ported from Geth:
    "tests a regular value transfer (no exec), and accessing the statedb in 'result'" in new JsTraceEnv {
      val js: String =
        s"""{
          |  depths: [],
          |  step: function() {},
          |  fault: function() {},
          |  result: function(ctx, db){ return db.getBalance(ctx.to)}
          |  }""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe true

      val opCode: OpCode = PUSH1
      tracer.step(opCode, state)

      tracer.enter(context)
      tracer.exit(context, state.toResult)

      val result = tracer.result(context, state.toResult)
      result shouldBe Right("0")
    }

    "tests db.getCode function" in new JsTraceEnv {
      val js: String =
        s"""{
           |  depths: [],
           |  step: function() {},
           |  fault: function() {},
           |  result: function(ctx, db){ return db.getCode(ctx.to)}
           |  }""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe true

      val opCode: OpCode = PUSH1
      tracer.step(opCode, state)

      tracer.enter(context)
      tracer.exit(context, state.toResult)

      val result = tracer.result(context, state.toResult)
      result shouldBe Right("[]")
    }

    "tests db.getNonce function" in new JsTraceEnv {
      val js: String =
        s"""{
           |  depths: [],
           |  step: function() {},
           |  fault: function() {},
           |  result: function(ctx, db){ return db.getNonce(ctx.to)}
           |  }""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe true

      val opCode: OpCode = PUSH1
      tracer.step(opCode, state)

      tracer.enter(context)
      tracer.exit(context, state.toResult)

      val result = tracer.result(context, state.toResult)
      result shouldBe Right("0")
    }

    "tests script returning just a string" in new JsTraceEnv {
      val js: String =
        s"""{
           |  depths: [],
           |  step: function() {},
           |  fault: function() {},
           |  result: function(ctx, db){ return ctx.type}
           |  }""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe true

      val opCode: OpCode = PUSH1
      tracer.step(opCode, state)

      tracer.enter(context)
      tracer.exit(context, state.toResult)

      val result = tracer.result(context, state.toResult)
      result shouldBe Right("\"CALL\"")
    }

    "tests script returning just a Long" in new JsTraceEnv {
      val js: String =
        s"""{
           |  depths: [],
           |  step: function() {},
           |  fault: function() {},
           |  result: function(ctx, db){ return ctx.gas}
           |  }""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe true

      val opCode: OpCode = PUSH1
      tracer.step(opCode, state)

      tracer.enter(context)
      tracer.exit(context, state.toResult)

      val result = tracer.result(context, state.toResult)
      result shouldBe Right("1000")
    }

    "tests script returning just a byte array" in new JsTraceEnv {
      import org.json4s.native.JsonMethods.parse

      val js: String =
        s"""{
           |  depths: [],
           |  step: function() {},
           |  fault: function() {},
           |  result: function(ctx, db){ return ctx.input}
           |  }""".stripMargin
      val tracer: JSTracer = new JSTracer(js)

      val initializationResult: Either[Throwable, Unit] = tracer.init(context)
      initializationResult.isRight shouldBe true

      val opCode: OpCode = PUSH1
      tracer.step(opCode, state)

      tracer.enter(context)
      tracer.exit(context, state.toResult)

      val traceResult = tracer.result(context, state.toResult)
      val parsedResult = traceResult.map { res =>
        val array =
          Extraction.extract(parse(res), TypeInfo(classOf[Array[Int]], None))(JSTracer.formats).asInstanceOf[Array[Int]]
        ByteString.fromInts(array: _*).utf8String
      }
      parsedResult shouldBe Right("input data")
    }
  }

  trait JsTraceEnv {
    protected val ownerAddr: Address        = Address(0x123456)
    protected val callerAddr: Address       = Address(0x1)
    protected val gas: BigInt               = BigInt(1000)
    protected val inputData: ByteString     = ByteString("input data")
    protected val blockHeader: BlockContext = ValidBlock.blockContext
    protected val evmConfig: EvmConfig      = EvmConfig.IstanbulConfigBuilder(blockchainConfig)

    def world: MockWorldState = MockWorldState()

    protected val context: ProgramContext[MockWorldState, MockStorage] = ProgramContext(
      callerAddr = callerAddr,
      originAddr = callerAddr,
      recipientAddr = Some(ownerAddr),
      maxPriorityFeePerGas = 0,
      startGas = gas,
      intrinsicGas = BigInt(3000),
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

    val state: ProgramState[MockWorldState, MockStorage] =
      ProgramState(callMethod, createMethod, selfDestructMethod, context, env)

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
