package io.iohk.scevm.utils

import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import akka.util.Timeout
import cats.effect.Async

import scala.reflect.ClassTag

object AkkaCatsOps {
  implicit class CatsActorOps(val to: ActorRef) extends AnyVal {

    def askFor[F[_]: Async, A](
        message: Any
    )(implicit timeout: Timeout, classTag: ClassTag[A], sender: ActorRef = Actor.noSender): F[A] = {
      val task = Async[F].delay((to ? message).mapTo[A])
      Async[F].fromFuture(task)
    }
  }
}
