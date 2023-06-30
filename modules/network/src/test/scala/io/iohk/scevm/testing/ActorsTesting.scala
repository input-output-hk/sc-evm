package io.iohk.scevm.testing

import akka.actor.{ActorRef, Identify}
import akka.pattern.ask
import akka.testkit.TestActor.AutoPilot
import akka.util.Timeout
import cats.effect.IO

object ActorsTesting {
  def simpleAutoPilot(makeResponse: PartialFunction[Any, Any]): AutoPilot =
    new AutoPilot {
      def run(sender: ActorRef, msg: Any) = {
        val response = makeResponse.lift(msg)
        response match {
          case Some(value) => sender ! value
          case _           => ()
        }
        this
      }
    }

  /** Returns an IO that resolves when the actor is completely started
    *
    * It can be useful if the actors does some actions on startup, such as subscribing to a topic.
    */
  def awaitActorReady(actor: ActorRef)(implicit timeout: Timeout): IO[Unit] =
    IO.fromFuture(IO(actor ? Identify(()))).map(_ => ())
}
