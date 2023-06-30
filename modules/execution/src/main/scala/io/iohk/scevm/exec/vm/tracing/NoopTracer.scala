package io.iohk.scevm.exec.vm.tracing

import io.iohk.scevm.exec.vm.tracing.Tracer.TracingError
import io.iohk.scevm.exec.vm.{OpCode, ProgramContext, ProgramResult, ProgramState}

final class NoopTracer extends Tracer {
  @inline
  override def init(pc: ProgramContext[_, _]): Either[Throwable, Unit] = Right(())

  @inline
  override def enter(programContext: ProgramContext[_, _]): Either[Throwable, Unit] = Right(())

  @inline
  override def exit(programContext: ProgramContext[_, _], programResult: ProgramResult[_, _]): Either[Throwable, Unit] =
    Right(())

  @inline
  override def step(opCode: OpCode, state: ProgramState[_, _]): Either[Throwable, Unit] = Right(())

  @inline
  override def fault(opCode: OpCode, state: ProgramState[_, _]): Either[Throwable, Unit] = Right(())

  @inline
  override def result(pc: ProgramContext[_, _], result: ProgramResult[_, _]): Either[TracingError, String] = Right("")
}
