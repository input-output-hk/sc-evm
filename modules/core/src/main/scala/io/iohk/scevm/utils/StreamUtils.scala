package io.iohk.scevm.utils

import cats.Applicative
import cats.syntax.all._
import fs2.Pull

object StreamUtils {
  implicit class RichFs2Stream[F[_], O](val stream: fs2.Stream[F, O]) extends AnyVal {
    def evalScan1[F2[x] >: F[x], O2 >: O](f: (O2, O2) => F[O2]): fs2.Stream[F2, O2] = {
      def go(z: O2, s: fs2.Stream[F2, O]): Pull[F2, O2, Unit] =
        s.pull.uncons1.flatMap {
          case Some((hd, tl)) =>
            Pull.eval(f(z, hd)).flatMap(o => Pull.output1(o) >> go(o, tl))
          case None => Pull.done
        }
      stream.pull.uncons.flatMap {
        case None => Pull.done
        case Some((hd, tl)) =>
          val (pre, post) = hd.splitAt(1)
          Pull.output(pre) >> go(pre(0), tl.cons(post))
      }.stream
    }

    def evalFilterWithPrevious(f: (O, O) => F[Boolean])(implicit ev: Applicative[F]): fs2.Stream[F, O] =
      stream
        .evalScan(Option.empty[Either[O, O]]) {
          case (Some(previous), current) =>
            f(previous.merge, current).map {
              case true  => Some(Right(current))
              case false => Some(Left(previous.merge))
            }
          case (None, current) => Applicative[F].pure(Some(Right(current)))
        }
        .collect { case Some(Right(value)) => value }
  }
}
