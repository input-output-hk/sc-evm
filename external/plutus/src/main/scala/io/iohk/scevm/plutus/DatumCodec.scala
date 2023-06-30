package io.iohk.scevm.plutus

import cats.Invariant
import cats.syntax.all._
import io.iohk.scevm.plutus.DatumDecoder.DatumDecodeError
import io.iohk.scevm.plutus.generic.DatumCodecDerivation

/** The design is inspired by
  * https://github.com/tpolecat/doobie/blob/35fbc3f551b561d0a3b905d89365b0b68c81f987/modules/core/src/main/scala/doobie/util/meta/meta.scala
  *
  * Convenience for introducing a symmetric `DatumEncoder`/`DatumDecoder` pair into implicit scope, and for deriving
  * new symmetric pairs. It's important to understand that `DatumCodec` should never be demanded by user
  * methods; instead demand both `DatumEncoder` and `DatumDecoder`. The reason for this is that while `DatumCodec` implies
  * `DatumEncoder` and `DatumDecoder`, the presence of both `DatumEncoder` and `DatumDecoder` does *not* imply `DatumCodec`.
  */
final case class DatumCodec[T] private (encoder: DatumEncoder[T], decoder: DatumDecoder[T]) {

  def dimap[T2](ef: DatumDecoder[T] => DatumDecoder[T2])(gf: DatumEncoder[T] => DatumEncoder[T2]): DatumCodec[T2] =
    DatumCodec[T2](gf(encoder), ef(decoder))
}

object DatumCodec extends DatumCodecDerivation {
  def apply[T](implicit ev: DatumCodec[T]): DatumCodec[T] = ev

  def make[T](encoder: DatumEncoder[T], decoder: DatumDecoder[T]): DatumCodec[T] = new DatumCodec[T](encoder, decoder)

  implicit val DatumCodecInvariant: Invariant[DatumCodec] =
    new Invariant[DatumCodec] {
      def imap[A, B](fa: DatumCodec[A])(f: A => B)(g: B => A): DatumCodec[B] =
        fa.imap(f)(g)
    }

  implicit class DatumCodecOps[T](datumCodec: DatumCodec[T]) {

    /** DatumCodec is an invariant functor */
    def imap[T2](f: T => T2)(g: T2 => T): DatumCodec[T2] = imapEither(f.andThen(Right.apply))(g)

    /** Variant of `imap` that allows the decoding to fail. */
    def imapEither[T2](f: T => Either[DatumDecodeError, T2])(g: T2 => T): DatumCodec[T2] =
      datumCodec.dimap(_.mapEither(f))(_.contramap(g))

    def nest: DatumCodec[T] = datumCodec.dimap(_.unwrap)(_.wrap)
  }
}
