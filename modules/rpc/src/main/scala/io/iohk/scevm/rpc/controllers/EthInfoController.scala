package io.iohk.scevm.rpc.controllers

import cats.effect.IO
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.rpc.ServiceResponse
import io.iohk.scevm.rpc.dispatchers.EmptyRequest

import scala.annotation.unused

class EthInfoController(blockchainConfig: BlockchainConfig) {

  def chainId(@unused req: EmptyRequest): ServiceResponse[Byte] = IO.pure(Right(blockchainConfig.chainId.value))
}
