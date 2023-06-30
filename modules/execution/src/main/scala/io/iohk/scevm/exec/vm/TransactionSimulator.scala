package io.iohk.scevm.exec.vm

import cats.effect.Sync
import cats.syntax.all._
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.domain.{Address, BlockContext, Transaction}
import io.iohk.scevm.exec.config.{BlockchainConfigForEvm, EvmConfig}

trait TransactionSimulator[F[_]] {

  def estimateGas(
      transaction: Transaction,
      senderAddress: Address = Address(0),
      blockContextMod: BlockContext => BlockContext = identity
  ): F[Either[ProgramResultError, BigInt]]

  def executeTx(
      transaction: Transaction,
      senderAddress: Address = Address(0),
      blockContextMod: BlockContext => BlockContext = identity
  ): F[PR]
}

object TransactionSimulator {
  def apply[F[_]: Sync](
      blockchainConfig: BlockchainConfig,
      vm: VM[F, WorldType, StorageType],
      prepareState: (WorldType) => F[WorldType]
  ): TransactionSimulator[EvmCall[F, *]] = new TransactionSimulatorImpl(blockchainConfig, vm, prepareState)
}

/** Helper to simulate a transaction in the EVM
  * @param prepareState function that will be used to create a modified world before running the transaction
  */
private class TransactionSimulatorImpl[F[_]: Sync](
    blockchainConfig: BlockchainConfig,
    vm: VM[F, WorldType, StorageType],
    prepareState: (WorldType) => F[WorldType]
) extends TransactionSimulator[EvmCall[F, *]] {

  override def estimateGas(
      transaction: Transaction,
      senderAddress: Address = Address(0),
      blockContextMod: BlockContext => BlockContext = identity
  ): EvmCall[F, Either[ProgramResultError, BigInt]] =
    for {
      vmRunResult <- executeTx(transaction, senderAddress, blockContextMod)
    } yield vmRunResult.toEither.map(_ => transaction.gasLimit - vmRunResult.gasRemaining)

  override def executeTx(
      transaction: Transaction,
      senderAddress: Address = Address(0),
      blockContextMod: BlockContext => BlockContext = identity
  ): EvmCall[F, PR] =
    EvmCall { world =>
      val worldWithCoinbase = world.modifyBlockContext(blockContextMod)
      for {
        state       <- prepareState(worldWithCoinbase)
        context      = buildProgramContext(senderAddress, transaction, state)
        vmRunResult <- vm.run(context)
      } yield vmRunResult
    }

  private def buildProgramContext(
      senderAddress: Address,
      transaction: Transaction,
      world: WorldType
  ): PC =
    ProgramContext(
      transaction = transaction,
      blockContext = world.blockContext,
      senderAddress = senderAddress,
      world = world,
      evmConfig = EvmConfig.forBlock(world.blockContext.number, BlockchainConfigForEvm(blockchainConfig))
    )
}
