package io.iohk.scevm.network.utils

import akka.actor.typed.ActorSystem
import cats.effect.kernel.{Async, Resource}
import cats.implicits.toFunctorOps

object TypedActorSystem {
  def apply[F[_]: Async](name: String): Resource[F, ActorSystem[Nothing]] =
    Resource
      .make(Async[F].delay(ActorSystem.wrap(akka.actor.ActorSystem(name)))) { system =>
        Async[F].fromFuture {
          Async[F].delay {
            system.terminate()
            system.whenTerminated
          }
        }.void
      }
}
