package io.iohk.scevm.exec

import cats.Functor
import cats.data.{Kleisli, ReaderT}
import cats.effect.IO
import io.iohk.scevm.domain.UInt256
import org.typelevel.log4cats.LoggerFactory

package object vm {

  type WorldType   = InMemoryWorldState
  type StorageType = InMemoryworldStateStorage

  type PC = ProgramContext[InMemoryWorldState, InMemoryworldStateStorage]
  type PR = ProgramResult[InMemoryWorldState, InMemoryworldStateStorage]

  /** Number of 32-byte UInt256s required to hold n bytes (~= math.ceil(n / 32))
    */
  def wordsForBytes(n: BigInt): BigInt =
    if (n == 0) 0 else (n - 1) / UInt256.Size + 1

  type EvmCall[F[_], A] = Kleisli[F, WorldType, A]

  implicit def loggerForEvmCall[F[_]: LoggerFactory: Functor]: LoggerFactory[EvmCall[F, *]] =
    LoggerFactory[F].mapK(Kleisli.liftK[F, WorldType])

  val EvmCall = ReaderT

  type IOEvmCall[A] = EvmCall[IO, A]
  val IOEvmCall = EvmCall
}
