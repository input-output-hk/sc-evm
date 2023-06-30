package io.iohk.scevm.plutus

import cats.Contravariant
import io.bullet.borer.Cbor
import io.iohk.bytes.ByteString
import io.iohk.scevm.plutus.generic.DatumEncoderDerivation

import scala.annotation.implicitNotFound

@implicitNotFound("Could not find implicit DatumEncoder for ${T}")
trait DatumEncoder[T] { self =>
  def encode(value: T): Datum
}

object DatumEncoder extends DatumEncoderDerivation {

  def apply[T](implicit enc: DatumEncoder[T]): DatumEncoder[T] = enc

  implicit def fromCodec[T](implicit codec: DatumCodec[T]): DatumEncoder[T] = codec.encoder

  implicit class EncoderOpsFromDatum(datum: Datum) {
    def toCborBytes: ByteString = ByteString(Cbor.encode(datum).toByteArray)
  }

  implicit class DatumEncoderOps[T](datumEncoder: DatumEncoder[T]) {

    /** Creates a new encoder that wraps encoded value with a datum constructor
      */
    def wrap: DatumEncoder[T] =
      (value: T) => ConstructorDatum(0, Vector(datumEncoder.encode(value)))
  }

  implicit val DatumEncoderContravariant: Contravariant[DatumEncoder] = new Contravariant[DatumEncoder] {
    override def contramap[A, B](fa: DatumEncoder[A])(f: B => A): DatumEncoder[B] = { (value: B) =>
      fa.encode(f(value))
    }
  }
}
