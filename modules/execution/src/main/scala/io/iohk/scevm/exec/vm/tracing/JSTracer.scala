package io.iohk.scevm.exec.vm.tracing

import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.domain.{Address, UInt256}
import io.iohk.scevm.exec.vm
import io.iohk.scevm.exec.vm.OpCodes.PushOpCodes
import io.iohk.scevm.exec.vm.tracing.JSTracer.{BuiltInFunctions, Ctx, DB, Log, _}
import io.iohk.scevm.exec.vm.tracing.Tracer.{TracingError, TracingException}
import io.iohk.scevm.exec.vm.{
  ExecEnv,
  OpCode,
  PrecompiledContracts,
  ProgramContext,
  ProgramResult,
  ProgramState,
  Storage,
  WorldState
}
import io.iohk.scevm.utils.Logger
import org.json4s.native.Serialization
import org.json4s.{CustomSerializer, DefaultFormats, Extraction, Formats, JNull, JObject, JValue}
import org.mozilla.javascript.annotations.{JSConstructor, JSFunction, JSGetter}
import org.mozilla.javascript.{Context, NativeJavaObject, NativeObject, Scriptable, ScriptableObject}

import scala.jdk.CollectionConverters._
import scala.util.Try

final class JSTracer(js: String) extends Tracer with Logger {
  private var scope: ScriptableObject = _
  private var withStep: Boolean       = false
  private var withEnterExit: Boolean  = false

  override def init(programContext: ProgramContext[_, _]): Either[Throwable, Unit] =
    withContext { cx =>
      scope = cx.initSafeStandardObjects()

      ScriptableObject.defineClass(scope, classOf[Log[_, _]])
      ScriptableObject.defineClass(scope, classOf[DB[_, _]])
      ScriptableObject.defineClass(scope, classOf[Ctx[_, _]])
      ScriptableObject.defineClass(scope, classOf[BuiltInFunctions[_, _]])
      ScriptableObject.defineClass(scope, classOf[CallFrame[_, _]])
      ScriptableObject.defineClass(scope, classOf[FrameResult[_, _]])

      val builtIn: Scriptable = cx.newObject(scope, JSTracer.BuiltInFunctionsName, Array(programContext))
      scope.put("Tracer", scope, builtIn)
      validate(scope, cx, js)
    }

  private def validate(scope: ScriptableObject, cx: Context, js: String): Unit = {
    val wrappedJS = s"var tracer = $js;"

    // Evaluate the string to see if it's a valid javascript code
    cx.evaluateString(scope, wrappedJS, "<tracer>", 1, null)

    // Check that required methods are correctly defined in the tracer object
    // `result` and `fault` are mandatory
    // and at least one of the following: `step` or (`enter` and `exit`)
    val tracerObject: NativeObject = scope.get("tracer", scope).asInstanceOf[NativeObject]
    if (!tracerObject.containsKey("fault")) {
      throw TracingException("Javascript-based tracing must define `fault` function.")
    } else if (!tracerObject.containsKey("result")) {
      throw TracingException("Javascript-based tracing must define `result` function.")
    }

    val containsStep  = tracerObject.containsKey("step")
    val containsEnter = tracerObject.containsKey("enter")
    val containsExit  = tracerObject.containsKey("exit")

    withStep = containsStep
    withEnterExit = containsEnter && containsExit

    if ((containsEnter && !containsExit) || (!containsEnter && containsExit))
      throw TracingException(
        "Javascript-based tracing must define both `enter` and `exit` functions (or only `step`)."
      )

    if (!(containsStep || withEnterExit)) {
      throw TracingException(
        "Javascript-based tracing must define `step` function or `enter` and `exit` functions (or all)."
      )
    }

  }

  override def result(
      programContext: ProgramContext[_, _],
      programResult: ProgramResult[_, _]
  ): Either[TracingError, String] =
    programResult.tracingError match {
      case None        => runResultTrace(programContext, programResult).left.map(ex => TracingError(ex))
      case Some(error) => Left(error)
    }

  private def runResultTrace(
      programContext: ProgramContext[_, _],
      programResult: ProgramResult[_, _]
  ): Either[Throwable, String] = withContext { context =>
    val ctx: Scriptable =
      context.newObject(scope, JSTracer.CtxConstructorName, Array(programContext, programResult))
    scope.put("ctx", scope, ctx)

    val db: Scriptable = context.newObject(scope, JSTracer.DbConstructorName, Array(programResult))
    scope.put("db", scope, db)

    val result = context.evaluateString(scope, "tracer.result(ctx, db)", "<result>", 1, null)
    val json   = Extraction.decompose(result)(JSTracer.formats)
    Serialization.write(json)(JSTracer.formats)
  }

  override def step(opCode: OpCode, state: ProgramState[_, _]): Either[Throwable, Unit] =
    if (!withStep) { Right(()) }
    else {
      withContext { context =>
        val jsTracerOp = new JSTracer.Op(opCode)
        val args       = Array(opCode, jsTracerOp, state)

        val evmTracerLog: Scriptable = context.newObject(scope, JSTracer.LogConstructorName, args)
        scope.put("log", scope, evmTracerLog)

        val db: Scriptable = context.newObject(scope, JSTracer.DbConstructorName, Array(state.toResult))
        scope.put("db", scope, db)

        context.evaluateString(scope, "tracer.step(log, db)", "<step>", 1, null)
      }
    }

  override def fault(opCode: OpCode, state: ProgramState[_, _]): Either[Throwable, Unit] =
    withContext { context =>
      val jsTracerOp = new JSTracer.Op(opCode)
      val args       = Array(opCode, jsTracerOp, state)

      val evmTracerLog: Scriptable = context.newObject(scope, JSTracer.LogConstructorName, args)
      scope.put("log", scope, evmTracerLog)

      val db: Scriptable = context.newObject(scope, JSTracer.DbConstructorName, Array(state.toResult))
      scope.put("db", scope, db)

      context.evaluateString(scope, "tracer.fault(log, db)", "<fault>", 1, null)
    }

  override def enter(programContext: ProgramContext[_, _]): Either[Throwable, Unit] =
    if (!withEnterExit) { Right(()) }
    else {
      withContext { context =>
        val callFrame: Scriptable = context.newObject(scope, JSTracer.CallFrameName, Array(programContext))
        scope.put("callFrame", scope, callFrame)

        context.evaluateString(scope, "tracer.enter(callFrame)", "<enter>", 1, null)
      }
    }

  override def exit(
      programContext: ProgramContext[_, _],
      programResult: ProgramResult[_, _]
  ): Either[Throwable, Unit] =
    if (!withEnterExit) { Right(()) }
    else {
      withContext { context =>
        val frameResult: Scriptable =
          context.newObject(scope, JSTracer.FrameResultName, Array(programContext, programResult))
        scope.put("frameResult", scope, frameResult)

        context.evaluateString(scope, "tracer.exit(frameResult)", "<exit>", 1, null)
      }
    }

  private def withContext[A](toExecute: Context => A): Either[Throwable, A] = {
    val context = Context.enter()
    context.setLanguageVersion(Context.VERSION_ES6)

    val result = Try(toExecute(context))
    result.failed.foreach(exception => log.warn(s"Javascript error during transaction tracing: ${exception.toString}"))
    Context.exit()
    result.toEither
  }
}

object JSTracer {

  val CtxConstructorName   = "JSTracer.Ctx"
  val LogConstructorName   = "JSTracer.Log"
  val DbConstructorName    = "JSTracer.DB"
  val BuiltInFunctionsName = "JSTracer.BuiltInFunctions"
  val CallFrameName        = "JSTracer.CallFrame"
  val FrameResultName      = "JSTracer.FrameResult"

  object NativeObjectJsonSerializer
      extends CustomSerializer[NativeObject](formats =>
        (
          PartialFunction.empty,
          { case nativeObject: NativeObject => nativeObjectJsonSerializerHelper(nativeObject, formats) }
        )
      )

  object NativeJavaObjectJsonSerializer
      extends CustomSerializer[NativeJavaObject](formats =>
        (
          PartialFunction.empty,
          { case nativeJavaObject: NativeJavaObject => objectJsonSerializerHelper(nativeJavaObject.unwrap(), formats) }
        )
      )

  object OptionNoneToJNullSerializer
      extends CustomSerializer[Option[_]](_ =>
        (
          PartialFunction.empty,
          { case None => JNull }
        )
      )

  object UInt256Serializer
      extends CustomSerializer[UInt256](formats =>
        (
          PartialFunction.empty,
          { case uInt256: UInt256 => Extraction.decompose(uInt256.bytes)(formats) }
        )
      )

  val formats: Formats =
    DefaultFormats + NativeObjectJsonSerializer + NativeJavaObjectJsonSerializer + OptionNoneToJNullSerializer + UInt256Serializer

  private def nativeObjectJsonSerializerHelper(nativeObject: NativeObject, formats: Formats): JObject =
    JObject(
      nativeObject.asScala.map { case (key, value) =>
        val valueAsJson = value match {
          case subNativeObject: NativeObject => nativeObjectJsonSerializerHelper(subNativeObject, formats)
          case _                             => Extraction.decompose(value)(formats)
        }
        key.toString -> valueAsJson
      }.toList
    )

  private def objectJsonSerializerHelper(obj: Object, formats: Formats): JValue = Extraction.decompose(obj)(formats)

  @JSConstructor
  class BuiltInFunctions[W <: WorldState[W, S], S <: Storage[S]](programContextObject: AnyRef)
      extends ScriptableObject {
    override def getClassName: String = BuiltInFunctionsName

    // to avoid Unsupported parameter exception, given that we are using a Java library
    // it only supports java standard objects so for scala only AnyVal subtypes and AnyRef are supported
    val context: ProgramContext[W, S] = programContextObject.asInstanceOf[ProgramContext[W, S]]

    // The zero-argument constructor used by Rhino runtime to create instances
    def this() = this(null)

    /** returns true if the given address belongs to a precompiled contract */
    @JSFunction
    def isPrecompiled(address: String): Boolean = PrecompiledContracts.getContracts(context).contains(Address(address))

    /** Converts a hex strings into a BigInt */
    @JSFunction
    def bigInt(value: String): BigInt = Hex.parseHexNumberUnsafe(value)
  }

  @JSConstructor
  class Ctx[W <: WorldState[W, S], S <: Storage[S]](programContextObject: AnyRef, programResultObject: AnyRef)
      extends ScriptableObject {

    val programContext: ProgramContext[W, S] = programContextObject.asInstanceOf[ProgramContext[W, S]]
    val programResult: ProgramResult[W, S]   = programResultObject.asInstanceOf[ProgramResult[W, S]]

    // The zero-argument constructor used by Rhino runtime to create instances
    def this() = this(
      null,
      null
    )

    /** one of the two values CALL and CREATE */
    @JSGetter
    def `type`: String = programContext.recipientAddr.fold("CREATE")(_ => "CALL")

    /** sender of the transaction */
    @JSGetter
    def from: String = programContext.originAddr.toString

    /** target of the transaction */
    @JSGetter
    def to: String = programContext.recipientAddr.fold("")(_.toString)

    /** input transaction data */
    @JSGetter
    def input: Array[Byte] = programContext.inputData.toArray

    /** gas budget of the transaction */
    @JSGetter
    def gas: Long = programContext.startGas.toLong

    /** gas price for the transaction */
    @JSGetter
    def gasPrice: Long = programContext.maxPriorityFeePerGas.toLong

    /** amount of gas used in executing the transaction (excludes txdata costs) */
    @JSGetter
    def gasUsed: Long = (programContext.startGas - programResult.gasRemaining).toLong

    /** transaction intrinsic gas */
    @JSGetter
    def intrinsicGas: Long = programContext.intrinsicGas.toLong

    /** amount to be transferred in wei */
    @JSGetter
    def value: BigInt = programContext.endowment.toBigInt

    /** block number */
    @JSGetter
    def block: Long = programContext.blockContext.number.toLong

    /** value returned from EVM */
    @JSGetter
    def output: Array[Byte] = programResult.returnData.toArray

    /** execution runtime */
    @JSGetter
    def time: String = programResult.executionTime.toString

    override def getClassName: String = CtxConstructorName
  }

  @JSConstructor
  class Log[W <: WorldState[W, S], S <: Storage[S]](opCodeObject: AnyRef, opObject: AnyRef, stateObject: AnyRef)
      extends ScriptableObject {

    val opCode: OpCode            = opCodeObject.asInstanceOf[OpCode]
    val opInstance: Op            = opObject.asInstanceOf[Op]
    val state: ProgramState[W, S] = stateObject.asInstanceOf[ProgramState[W, S]]

    // The zero-argument constructor used by Rhino runtime to create instances
    def this() = this(
      null,
      null,
      null
    )

    /** an OpCode object representing the current opcode */
    @JSGetter
    def op: Op = opInstance

    /** the EVM execution stack */
    @JSGetter
    def stack: Stack = new Stack(state.stack)

    /** a structure representing the contract’s memory space */
    @JSGetter
    def memory: Memory = new Memory(state.memory)

    /** an object representing the account executing the current operation */
    @JSGetter
    def contract: Contract = new Contract(state.env)

    /** returns a Number with the current program counter */
    @JSFunction
    def getPC(): Int = state.pc

    /** returns a Number with the amount of gas remaining */
    @JSFunction
    def getGas(): Long = state.gas.toLong

    /** returns the cost of the opcode as a Number */
    @JSFunction
    def getCost(): Long = opCode.calcGas(state).toLong

    /** returns the execution depth as a Number */
    @JSFunction
    def getDepth(): Int = state.env.callDepth

    /** returns the amount to be refunded as a Number */
    @JSFunction
    def getRefund(): Long = state.gasRefund.toLong

    /** returns information about the error if one occurred, otherwise returns undefined */
    @JSFunction
    def getError(): String = state.error.fold[String](null)(_.toString())

    override def getClassName: String = LogConstructorName
  }

  class Op(opCode: OpCode) {

    /** returns true if the opcode is a PUSHn */
    def isPush(): Boolean = PushOpCodes.contains(opCode)

    /** returns the string representation of the opcode */
    override def toString(): String = opCode.getClass().getSimpleName().init

    /** returns the opcode’s number */
    def toNumber(): Int = opCode.code.toInt
  }

  class Memory(memory: io.iohk.scevm.exec.vm.Memory) {

    /** returns the specified segment of memory as a byte slice */
    def slice(start: Int, stop: Int): Seq[Byte] = {
      if (start < 0 || stop < 0)
        throw TracingException(
          s"Out of memory bounds error: Memory.slice function was invoked with parameters start: $start and stop: $stop"
        )
      val (sliceMemory, _) = memory.load(UInt256(start), UInt256(stop - start))
      sliceMemory.toList
    }

    /** returns the 32 bytes at the given offset */
    def getUint(offset: Int): Seq[Byte] = {
      if (offset < 0)
        throw TracingException(
          s"Out of memory bounds error: Memory.getUint function was invoked with parameter offset: $offset"
        )
      val (value, _) = memory.load(UInt256(offset))
      value.bytes
    }

    /** returns memory size */
    def length(): Int = memory.size
  }

  class Stack(stack: vm.Stack) {

    /** returns the idx-th element from the top of the stack (0 is the topmost element) as a big.Int */
    def peek(idx: Int): BigInt = {
      if (idx < 0)
        throw TracingException(
          s"Out of stack bounds error: Stack.peek function was invoked with parameter index: $idx"
        )
      stack.toSeq.lift(idx).fold(BigInt(0))(_.toBigInt)
    }

    /** returns the number of elements in the stack */
    def length(): Int = stack.size
  }

  class Contract(env: ExecEnv) {

    /** returns the address of the caller */
    def getCaller(): String = env.callerAddr.toString

    /** returns the address of the current contract */
    def getAddress(): String = env.ownerAddr.toString

    /** returns the amount of value sent from caller to contract as a big.Int */
    def getValue(): BigInt = env.value.toBigInt

    /** returns the input data passed to the contract */
    def getInput(): Seq[Byte] = env.inputData
  }

  @JSConstructor
  class DB[W <: WorldState[W, S], S <: Storage[S]](programResultObject: AnyRef) extends ScriptableObject {
    val programResult: ProgramResult[W, S] = programResultObject.asInstanceOf[ProgramResult[W, S]]

    // The zero-argument constructor used by Rhino runtime to create instances
    def this() = this(null)

    /** returns a big.Int with the specified account’s balance */
    @JSFunction
    def getBalance(address: String): BigInt = programResult.world.getBalance(Address(address))

    /** returns a Number with the specified account’s nonce */
    @JSFunction
    def getNonce(address: String): Int =
      programResult.world.getAccount(Address(address)).map(_.nonce.value.toInt).getOrElse(0)

    /** returns a byte slice with the code for the specified account */
    @JSFunction
    def getCode(address: String): Seq[Byte] = programResult.world.getCode(Address(address))

    /** returns the state value for the specified account and the specified offset */
    @JSFunction
    def getState(address: String, hash: String): BigInt =
      programResult.world.getStorage(Address(address)).load(Hex.parseHexNumberUnsafe(hash))

    /** returns true if the specified address exists */
    @JSFunction
    def exists(address: String): Boolean = programResult.world.accountExists(Address(address))

    override def getClassName: String = DbConstructorName
  }

  @JSConstructor
  class CallFrame[W <: WorldState[W, S], S <: Storage[S]](programContextObject: AnyRef) extends ScriptableObject {
    val programContext: ProgramContext[W, S] = programContextObject.asInstanceOf[ProgramContext[W, S]]

    // The zero-argument constructor used by Rhino runtime to create instances
    def this() = this(null)

    /** returns a string which has the type of the call frame */
    @JSFunction
    def getType(): String = programContext.recipientAddr.fold("CREATE")(_ => "CALL")

    /** returns the address of the call frame sender */
    @JSFunction
    def getFrom(): String = programContext.originAddr.toString

    /** returns the address of the call frame target */
    @JSFunction
    def getTo(): String = programContext.recipientAddr.fold("")(_.toString)

    /** returns the input as a buffer */
    @JSFunction
    def getInput(): Array[Byte] = programContext.inputData.toArray

    /** returns a Number which has the amount of gas provided for the frame */
    @JSFunction
    def getGas(): Long = programContext.startGas.toLong

    /** returns a big.Int with the amount to be transferred only if available, otherwise undefined */
    @JSFunction
    def getValue(): BigInt = programContext.endowment.toBigInt

    override def getClassName: String = CallFrameName
  }

  class FrameResult[W <: WorldState[W, S], S <: Storage[S]](programContextObject: AnyRef, programResultObject: AnyRef)
      extends ScriptableObject {

    val programContext: ProgramContext[W, S] = programContextObject.asInstanceOf[ProgramContext[W, S]]
    val programResult: ProgramResult[W, S]   = programResultObject.asInstanceOf[ProgramResult[W, S]]

    // The zero-argument constructor used by Rhino runtime to create instances
    def this() = this(
      null,
      null
    )

    /** returns amount of gas used throughout the frame as a Number */
    @JSFunction
    def getGasUsed(): Long = (programContext.startGas - programResult.gasRemaining).toLong

    /** returns the output as a buffer */
    @JSFunction
    def getOutput(): Array[Byte] = programResult.returnData.toArray

    /** returns an error if one occurred during execution and undefined otherwise */
    @JSFunction
    def getError(): String = programResult.error.fold[String](null)(_.toString())

    override def getClassName: String = FrameResultName
  }

}
