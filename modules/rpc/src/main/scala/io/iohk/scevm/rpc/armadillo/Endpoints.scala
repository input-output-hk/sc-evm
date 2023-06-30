package io.iohk.scevm.rpc.armadillo

import cats.Functor
import cats.data.EitherT
import io.iohk.armadillo.JsonRpcError
import io.iohk.scevm.rpc.ServiceResponseF
import org.json4s.JsonAST.{JNull, JValue}

trait Endpoints {
  implicit protected class ResponseExtensions[F[_], T](response: ServiceResponseF[F, T]) {

    def noDataError(implicit ev: Functor[F]): EitherT[F, JsonRpcError[Unit], T] =
      EitherT(response).leftMap(error => JsonRpcError.noData(error.code, error.message))

    def noDataErrorMapValue[TT](f: T => TT)(implicit ev: Functor[F]): F[Either[JsonRpcError[Unit], TT]] =
      noDataError.map(f).value

    def withData(implicit ev: Functor[F]): EitherT[F, JsonRpcError[JValue], T] =
      EitherT(response).leftMap { error =>
        val data = error.data match {
          case Some(data) => data
          case None       => JNull
        }
        JsonRpcError.withData(error.code, error.message, data)
      }

    def withDataMapValue[TT](f: T => TT)(implicit ev: Functor[F]): F[Either[JsonRpcError[JValue], TT]] =
      withData.map(f).value
  }

}

object Endpoints extends Endpoints
