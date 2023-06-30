package io.iohk.scevm.testnode

import cats.Functor
import cats.syntax.all._
import io.iohk.scevm.domain.{Address, MemorySlot, Token}
import io.iohk.scevm.rpc.ServiceResponseF
import io.iohk.scevm.rpc.domain.JsonRpcError
import io.iohk.scevm.testnode.TestJRpcController.ScEvmNodeProxy

class HardhatApiController[F[_]: Functor](proxy: ScEvmNodeProxy[F]) {

  def setStorage(address: Address, memorySlot: MemorySlot, value: Token): ServiceResponseF[F, Boolean] =
    proxy.addStorageModification(address, memorySlot, value).as(true.asRight[JsonRpcError])
}
