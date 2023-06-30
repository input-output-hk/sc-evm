package io.iohk.scevm.exec.vm.tracing

import io.iohk.scevm.exec.vm.tracing.Tracer.TracingError
import io.iohk.scevm.exec.vm.{OpCode, ProgramContext, ProgramResult, ProgramState}

trait Tracer {

  /** Initializes the tracing operation */
  def init(pc: ProgramContext[_, _]): Either[Throwable, Unit]

  /** Called when EVM enters a new scope (via call or create) */
  def enter(programContext: ProgramContext[_, _]): Either[Throwable, Unit]

  /** Called when EVM exits a scope, even if the scope didn't execute any code */
  def exit(programContext: ProgramContext[_, _], programResult: ProgramResult[_, _]): Either[Throwable, Unit]

  /** Traces a single step of the VM execution */
  def step(opCode: OpCode, state: ProgramState[_, _]): Either[Throwable, Unit]

  /** Invoked when an error happens during the execution of an opcode which was not reported in step.
    * The method log.getError() has information about the error.
    */
  def fault(opCode: OpCode, state: ProgramState[_, _]): Either[Throwable, Unit]

  /** Called at the end of the tracing, returning a JSON-serializable value
    */
  def result(pc: ProgramContext[_, _], result: ProgramResult[_, _]): Either[TracingError, String]
}

object Tracer {

  final case class TracingError(exception: Throwable) {
    override def toString: String = this.exception.toString
  }

  final case class TracingException(message: String) extends RuntimeException(message) {
    override def toString: String = this.getMessage

    // Skipped to reduce performance overhead since VM tracing implementation throws a lot of exceptions
    override def fillInStackTrace(): Throwable = this
  }

}
