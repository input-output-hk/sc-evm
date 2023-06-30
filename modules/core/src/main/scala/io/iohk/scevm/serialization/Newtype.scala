package io.iohk.scevm.serialization

/** This is a type class to signal that a type is in fact a wrapper used for type safety.
  * It allow to automatically benefit from the underlying type serialization if it already
  * exist (the type will be serialized/deserialized with its underlying type without overhead).
  * @tparam Wrapper outer type
  * @tparam Underlying type which is wrapped
  */
trait Newtype[Wrapper, Underlying] {
  def wrap(underlying: Underlying): Wrapper
  def unwrap(wrapper: Wrapper): Underlying
}

object Newtype {
  def apply[Wrapper, Underlying](
      _wrap: Underlying => Wrapper,
      _unwrap: Wrapper => Underlying
  ): Newtype[Wrapper, Underlying] =
    new Newtype[Wrapper, Underlying] {
      def wrap(underlying: Underlying): Wrapper = _wrap(underlying)
      def unwrap(wrapper: Wrapper): Underlying  = _unwrap(wrapper)
    }

  def applyUnsafe[Wrapper, Underlying](
      _wrap: Underlying => Either[String, Wrapper],
      _unwrap: Wrapper => Underlying
  ): Newtype[Wrapper, Underlying] =
    new Newtype[Wrapper, Underlying] {
      def wrap(underlying: Underlying): Wrapper = _wrap(underlying) match {
        case Left(error)  => throw new RuntimeException(s"Couldn't create newType because $error")
        case Right(value) => value
      }
      def unwrap(wrapper: Wrapper): Underlying = _unwrap(wrapper)
    }
}
