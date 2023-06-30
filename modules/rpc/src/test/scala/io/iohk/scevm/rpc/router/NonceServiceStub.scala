package io.iohk.scevm.rpc.router

import cats.Applicative
import io.iohk.scevm.domain.{Address, Nonce}
import io.iohk.scevm.rpc.NonceService

class NonceServiceStub[F[_]: Applicative](var currentNonce: Nonce = Nonce.Zero) extends NonceService[F] {

  override def getNextNonce(address: Address): F[Nonce] = Applicative[F].pure {
    val oldValue = currentNonce
    currentNonce = currentNonce.increaseOne
    oldValue
  }
}
