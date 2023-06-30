package io.iohk.scevm.rpc.http

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import cats.effect.Resource
import cats.effect.kernel.Async
import cats.syntax.all._
import io.iohk.scevm.network.utils.TypedActorSystem
import io.iohk.scevm.rpc.JsonRpcHttpServerConfig
import io.iohk.scevm.utils.Logger

import scala.concurrent.duration.DurationInt

class JsonRpcHttpServer[F[_]: Async](route: Route, config: JsonRpcHttpServerConfig)(implicit
    val actorSystem: ActorSystem
) extends Logger {

  def run(): Resource[F, Unit] =
    if (config.enabled) {
      runServer()
    } else {
      Resource.eval(Async[F].delay(log.info("JSON RPC HTTP server is disabled")))
    }

  private def runServer(): Resource[F, Unit] = {
    val acquire = (for {
      binding <-
        Async[F].fromFuture(Async[F].delay(Http(actorSystem).newServerAt(config.interface, config.port).bind(route)))
      _ <- Async[F].delay(log.info(s"JSON RPC HTTP server listening on ${binding.localAddress}"))
    } yield binding)
      .onError(ex => Async[F].delay(log.error("Cannot start JSON HTTP RPC server", ex)))
    Resource.make(acquire)(binding => Async[F].delay(binding.terminate(10.seconds))).void
  }
}

object JsonRpcHttpServer {

  def apply[F[_]: Async](appRoutes: () => Route, config: JsonRpcHttpServerConfig): Resource[F, JsonRpcHttpServer[F]] = {
    val route = HttpRoutesBuilder(config, appRoutes)

    TypedActorSystem[F]("sc_evm_http_server").map(_.classicSystem).map { actorSystem =>
      new JsonRpcHttpServer[F](route, config)(Async[F], actorSystem)
    }
  }
}
