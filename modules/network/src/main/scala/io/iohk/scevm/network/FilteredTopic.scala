package io.iohk.scevm.network

import cats.effect.{Concurrent, Resource, Sync}
import cats.syntax.all._
import fs2.concurrent.Topic
import fs2.concurrent.Topic.Closed
import fs2.{Pipe, Stream}

class FilteredTopic[F[_]: Sync, T] private (topic: Topic[F, T], predicate: T => F[Boolean]) extends Topic[F, T] {
  def subscribe(maxQueue: Int): Stream[F, T] = topic.subscribe(maxQueue)

  def subscribeAwait(maxQueue: Int): Resource[F, Stream[F, T]] = topic.subscribeAwait(maxQueue)

  def publish1(element: T): F[Either[Topic.Closed, Unit]] =
    predicate(element).flatMap { isKept =>
      if (isKept) {
        topic.publish1(element)
      } else {
        Sync[F].pure(Right(()))
      }
    }

  override def publish: Pipe[F, T, Nothing] = { in =>
    (in ++ Stream.exec(close.void))
      .evalMap(publish1)
      .takeWhile(_.isRight)
      .drain
  }

  override def subscribers: Stream[F, Int] = topic.subscribers

  override def close: F[Either[Closed, Unit]] = topic.close

  override def isClosed: F[Boolean] = topic.isClosed

  override def closed: F[Unit] = topic.closed
}

object FilteredTopic {
  def apply[F[_]: Concurrent: Sync, T](predicate: T => F[Boolean]): Resource[F, Topic[F, T]] =
    for {
      topic <- Resource.eval(Topic[F, T])
    } yield new FilteredTopic(topic, predicate)
}
