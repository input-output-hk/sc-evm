package io.iohk.scevm

import cats.effect.IO
import io.iohk.scevm.rpc.domain.JsonRpcError

package object rpc {
  type ServiceResponse[T] = IO[Either[JsonRpcError, T]]

  type ServiceResponseF[F[_], T] = F[Either[JsonRpcError, T]]

  // Represents fields that are present but always null in keeping with geth rpc implementation
  sealed trait AlwaysNull
  case object AlwaysNull extends AlwaysNull
}
