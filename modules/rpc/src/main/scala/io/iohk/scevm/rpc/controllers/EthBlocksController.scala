package io.iohk.scevm.rpc.controllers

import cats.effect.IO
import cats.syntax.all._
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{BlockHash, BlockNumber}
import io.iohk.scevm.ledger.BlockProvider
import io.iohk.scevm.rpc.ServiceResponse
import io.iohk.scevm.rpc.controllers.BlockResolver.ResolvedBlock
import io.iohk.scevm.rpc.dispatchers.EmptyRequest
import io.iohk.scevm.rpc.domain.RpcBlockResponse

import scala.annotation.unused

object EthBlocksController {
  final case class GetBlockByHashRequest(blockHash: BlockHash, fullTxs: Boolean)
  final case class GetBlockByHashResponse(blockResponse: Option[RpcBlockResponse])

  final case class GetBlockByNumberRequest(block: BlockParam, fullTxs: Boolean)
  final case class GetBlockByNumberResponse(blockResponse: Option[RpcBlockResponse])
}

class EthBlocksController(
    resolveBlock: BlockResolver[IO],
    blockProvider: BlockProvider[IO],
    chainId: ChainId
) {
  import EthBlocksController._

  /** eth_blockNumber that returns the number of most recent block.
    *
    * @return Current block number the client is on.
    */
  def bestBlockNumber(@unused req: EmptyRequest): ServiceResponse[BlockNumber] =
    for {
      header <- blockProvider.getBestBlockHeader
    } yield Right(header.number.some.getOrElse(BlockNumber(0)))

  /** Implements the eth_getBlockByHash method that fetches a requested block.
    *
    * @param request with the hash of the block requested
    * @return the block requested, None if the client doesn't have the block or an error if it's invalid
    */
  def getBlockByHash(request: GetBlockByHashRequest): ServiceResponse[GetBlockByHashResponse] = {
    val GetBlockByHashRequest(blockHash, fullTxs) = request

    blockProvider.getBlock(blockHash).map { blockOpt =>
      blockOpt.map(block => RpcBlockResponse.from(block, chainId, fullTxs)) match {
        case Some(responseOrError) => responseOrError.map(response => GetBlockByHashResponse(Some(response)))
        case None                  => Right(GetBlockByHashResponse(None))
      }
    }
  }

  /** Implements the eth_getBlockByNumber method that fetches a requested block.
    *
    * @param request with the block requested (by it's number or by tag)
    * @return the block requested or None if the client doesn't have the block
    */
  def getBlockByNumber(request: GetBlockByNumberRequest): ServiceResponse[GetBlockByNumberResponse] = {
    val GetBlockByNumberRequest(blockParam, fullTxs) = request

    resolveBlock.resolveBlock(blockParam).map {
      case Some(ResolvedBlock(block, pending)) =>
        RpcBlockResponse
          .from(block, chainId, fullTxs, pending)
          .map(response => GetBlockByNumberResponse(Some(response)))
      case None => Right(GetBlockByNumberResponse(None))
    }
  }

  /** Implements the eth_getBlockTransactionCountByHash method that fetches the number of txs that a certain block has.
    *
    * @param blockHash the hash of the block requested
    * @return the number of txs that the block has or None if the client doesn't have the block requested
    */
  def getBlockTransactionCountByHash(blockHash: BlockHash): ServiceResponse[Option[Int]] =
    blockProvider
      .getBlock(blockHash)
      .map(_.map(_.body.transactionList.length))
      .map(txCount => Right(txCount))

  /** Implements the eth_getBlockTransactionCountByNumber method that fetches the number of txs that a certain block has.
    *
    * @param blockParam the number of the block requested
    * @return the number of txs that the block has or None if the client doesn't have the block requested
    */
  def getBlockTransactionCountByNumber(blockParam: BlockParam): ServiceResponse[BigInt] =
    resolveBlock
      .resolveBlock(blockParam)
      .map {
        case None                          => BigInt(0).asRight
        case Some(ResolvedBlock(block, _)) => BigInt(block.body.transactionList.length).asRight
      }

}
