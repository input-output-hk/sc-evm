package io.iohk.scevm.plutus.generic

import io.iohk.scevm.plutus.DatumDecoder.{DatumConstructorOps, DatumDecodeError, DatumOpsFromDatum}
import io.iohk.scevm.plutus.{Datum, DatumDecoder}
import magnolia1._

import scala.language.experimental.macros

trait DatumDecoderDerivation {
  type Typeclass[T] = DatumDecoder[T]

  def join[T](ctx: CaseClass[Typeclass, T]): Typeclass[T] = new Typeclass[T] {
    override def decode(datum: Datum): Either[DatumDecoder.DatumDecodeError, T] =
      ctx.constructMonadic { param =>
        for {
          constr <- datum.asConstructor
          _ <-
            Either.cond(
              constr.fields.size >= ctx.parameters.size,
              (),
              DatumDecodeError(s"Field number mismatch when deserializing ${ctx.typeName.short}")
            )
          rawField     <- constr.getField(param.index)
          decodedField <- param.typeclass.decode(rawField)
        } yield decodedField
      }
  }

  def split[T](ctx: SealedTrait[Typeclass, T]): Typeclass[T] = { param =>
    param.asConstructor.flatMap { constr =>
      val subtype = ctx.subtypes.find(_.index == constr.constructor).get
      subtype.typeclass.decode(constr)
    }
  }

  def derive[T]: Typeclass[T] = macro Magnolia.gen[T]
}
