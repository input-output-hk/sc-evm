package io.iohk.scevm.plutus.generic

import io.iohk.scevm.plutus.{ConstructorDatum, Datum, DatumEncoder}
import magnolia1._

import scala.language.experimental.macros

trait DatumEncoderDerivation {
  type Typeclass[T] = DatumEncoder[T]

  def join[T](ctx: CaseClass[Typeclass, T]): Typeclass[T] = (value: T) =>
    ConstructorDatum(
      0,
      ctx.parameters.map { p =>
        p.typeclass.encode(p.dereference(value))
      }.toVector
    )

  def split[T](ctx: SealedTrait[Typeclass, T]): Typeclass[T] =
    (value: T) =>
      ctx.split(value) { sub =>
        val datum: Datum = sub.typeclass.encode(sub.cast(value))
        datum.asInstanceOf[ConstructorDatum].copy(constructor = sub.index)
      }

  def derive[T]: Typeclass[T] = macro Magnolia.gen[T]
}
