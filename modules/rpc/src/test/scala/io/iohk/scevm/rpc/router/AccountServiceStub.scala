package io.iohk.scevm.rpc.router

import cats.Applicative
import cats.syntax.all._
import io.iohk.scevm.domain.{Account, Address, ObftHeader}
import io.iohk.scevm.rpc.AccountService

final case class AccountServiceStub[F[_]: Applicative](result: Account) extends AccountService[F] {
  override def getAccount(header: ObftHeader, address: Address): F[Account] = result.pure[F]
}
