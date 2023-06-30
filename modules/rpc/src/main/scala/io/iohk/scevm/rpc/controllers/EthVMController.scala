package io.iohk.scevm.rpc.controllers

import cats.data.EitherT
import cats.effect.IO
import io.iohk.bytes.ByteString
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.domain.{
  Account,
  Address,
  BlockContext,
  Gas,
  Nonce,
  ObftHeader,
  Token,
  Transaction,
  TransactionType02
}
import io.iohk.scevm.exec.vm._
import io.iohk.scevm.rpc.ServiceResponse
import io.iohk.scevm.rpc.controllers.BlockResolver.ResolvedBlock
import io.iohk.scevm.rpc.controllers.EthVMController._
import io.iohk.scevm.rpc.domain.{JsonRpcError, JsonRpcServerErrorCodes}

/** This service contains RPC call relating the running code on the EVM */
class EthVMController(
    blockchainConfig: BlockchainConfig,
    resolveBlock: BlockResolver[IO],
    transactionSimulator: TransactionSimulator[EvmCall[IO, *]],
    worldStateBuilder: WorldStateBuilder[IO]
) {

  def call(req: CallRequest): ServiceResponse[CallResponse] =
    resolveBlock.resolveBlock(req.block).flatMap {
      case None => IO.pure(Left(JsonRpcError.BlockNotFound))
      case Some(ResolvedBlock(block, _)) =>
        val transaction = req.tx.toTransaction(req.tx.gas.map(_.value).getOrElse(block.header.gasLimit))
        val sender      = req.tx.from.map(Address.apply).getOrElse(Address(0))
        val result = buildWorld(sender, transaction, block.header).use(
          transactionSimulator
            .executeTx(
              transaction,
              sender
            )
            .run
        )
        result.map { result =>
          result.error match {
            case None        => Right(CallResponse(result.returnData))
            case Some(error) => Left(EthVMController.toExecutionError(error, result.returnData))
          }
        }
    }

  def estimateGas(req: EstimateGasRequest): ServiceResponse[Gas] =
    (for {
      ResolvedBlock(block, _) <- EitherT.fromOptionF(
                                   resolveBlock.resolveBlock(req.block.getOrElse(BlockParam.Latest)),
                                   JsonRpcError.BlockNotFound
                                 )
      transaction = req.tx.toTransaction(block.header.gasLimit)
      sender      = req.tx.from.map(Address.apply).getOrElse(Address(0))
      consumedGas <- EitherT(
                       buildWorld(sender, transaction, block.header).use(
                         transactionSimulator
                           .estimateGas(
                             transaction,
                             sender
                           )
                           .run
                       )
                     ).leftMap(err => toExecutionError(err.error, err.data))
    } yield Gas(block.header.gasLimit.min(consumedGas + (consumedGas * 8 / 100)))).value

  private def buildWorld(senderAddress: Address, transaction: Transaction, header: ObftHeader) =
    worldStateBuilder.getWorldStateForBlock(header.stateRoot, BlockContext.from(header)).map { realWorld =>
      val worldWithAccount = if (realWorld.getAccount(senderAddress).isEmpty) {
        realWorld.saveAccount(senderAddress, Account.empty(blockchainConfig.genesisData.accountStartNonce))
      } else
        realWorld

      updateSenderAccountBeforeExecution(transaction, senderAddress, worldWithAccount)
    }

  private def updateSenderAccountBeforeExecution(
      transaction: Transaction,
      senderAddress: Address,
      worldState: InMemoryWorldState
  ): InMemoryWorldState = {
    val account = worldState.getGuaranteedAccount(senderAddress)
    worldState.saveAccount(
      senderAddress,
      account.increaseBalance(-transaction.calculateUpfrontGas).increaseNonceOnce
    )
  }
}

object EthVMController {

  final case class CallRequest(tx: CallTransaction, block: EthBlockParam)
  final case class EstimateGasRequest(tx: CallTransaction, block: Option[EthBlockParam])
  final case class CallResponse(returnData: ByteString)

  final case class CallTransaction(
      from: Option[ByteString],
      to: Option[Address],
      gas: Option[Gas],
      gasPrice: Option[Token],
      value: Token,
      input: ByteString
  ) {
    def toTransaction(defaultGasLimit: BigInt): Transaction =
      TransactionType02(
        chainId = 0,
        nonce = Nonce.Zero,
        maxPriorityFeePerGas = gasPrice.map(_.value).getOrElse(BigInt(0)),
        maxFeePerGas = gasPrice.map(_.value).getOrElse(BigInt(0)),
        gasLimit = gas.map(_.value).getOrElse(defaultGasLimit),
        receivingAddress = to,
        value = value.value,
        payload = input,
        accessList = Nil
      )
  }

  def toExecutionError(error: ProgramError, returnData: ByteString): JsonRpcError =
    error match {
      case RevertOccurs =>
        val decodedError = RevertReason.decode(returnData.toArray)
        val message      = s"Execution reverted: $decodedError"
        JsonRpcError(JsonRpcServerErrorCodes.ServerError, message, None)
      case InvalidOpCode(opcode) => JsonRpcError(JsonRpcServerErrorCodes.ServerError, s"Invalid opcode: $opcode", None)
      case InvalidCall           => JsonRpcError(JsonRpcServerErrorCodes.ServerError, "Invalid call", None)
      case InvalidJump(_)        => JsonRpcError(JsonRpcServerErrorCodes.ServerError, "Invalid jump", None)
      case OpCodeNotAvailableInStaticContext(_) =>
        JsonRpcError(JsonRpcServerErrorCodes.ServerError, "Opcode is not available in static context", None)
      case OutOfGas => JsonRpcError(JsonRpcServerErrorCodes.ServerError, "Out of gas", None)
      case PreCompiledContractFail =>
        JsonRpcError(JsonRpcServerErrorCodes.ServerError, "Precompiled contract failure", None)
      case ReturnDataOverflow => JsonRpcError(JsonRpcServerErrorCodes.ServerError, "Return data overflow", None)
      case StackOverflow      => JsonRpcError(JsonRpcServerErrorCodes.ServerError, "Stack overflow", None)
      case StackUnderflow     => JsonRpcError(JsonRpcServerErrorCodes.ServerError, "Invalid underflow", None)
    }

}
