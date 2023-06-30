package io.iohk.scevm.rpc.controllers

import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all._
import cats.{Applicative, Show}
import io.estatico.newtype.macros.newtype
import io.iohk.bytes.ByteString
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.consensus.WorldStateBuilder
import io.iohk.scevm.consensus.pos.{CurrentBranch, ObftBlockExecution}
import io.iohk.scevm.db.storage.{BlocksReader, GetTransactionBlockService}
import io.iohk.scevm.domain.{
  Address,
  BlockContext,
  ObftBlock,
  ObftHeader,
  SignedTransaction,
  TransactionHash,
  TransactionLocation
}
import io.iohk.scevm.exec.config.EvmConfig
import io.iohk.scevm.exec.validators.SignedTransactionValidatorImpl
import io.iohk.scevm.exec.vm.tracing.Tracer.TracingError
import io.iohk.scevm.exec.vm.tracing.{JSTracer, Tracer}
import io.iohk.scevm.exec.vm.{EVM, InMemoryWorldState, PC, PR, ProgramContext, ProgramResult, StorageType, WorldType}
import io.iohk.scevm.ledger.TransactionExecution.TxsExecutionError
import io.iohk.scevm.ledger.{BeneficiaryRewardAddressProvider, TransactionExecutionImpl}
import io.iohk.scevm.rpc.controllers.DebugController.{TraceTransactionRequest, TraceTransactionResponse}
import io.iohk.scevm.rpc.domain.JsonRpcError
import io.iohk.scevm.rpc.{ServiceResponse, ServiceResponseF}
import io.iohk.scevm.serialization.Newtype

import scala.concurrent.duration.{Duration, FiniteDuration}

trait DebugController[F[_]] {
  def traceTransaction(request: TraceTransactionRequest): ServiceResponseF[F, TraceTransactionResponse]
}

object DebugController {
  final case class TraceParameters(tracer: String, timeout: Option[FiniteDuration])

  final case class TraceTransactionRequest(
      transactionHash: TransactionHash,
      traceParameters: TraceParameters
  )

  import scala.language.implicitConversions
  @newtype final case class TraceTransactionResponse(value: String)

  object TraceTransactionResponse {
    implicit val show: Show[TraceTransactionResponse] = Show.show(t => show"TraceTransactionResponse(${t.value})")

    implicit val valueClass: Newtype[TraceTransactionResponse, String] =
      Newtype[TraceTransactionResponse, String](TraceTransactionResponse.apply, _.value)

    // Manifest for @newtype classes - required for now, as it's required by json4s (which is used by Armadillo)
    implicit val manifest: Manifest[TraceTransactionResponse] = new Manifest[TraceTransactionResponse] {
      override def runtimeClass: Class[_] = TraceTransactionResponse.getClass
    }
  }
}

class DebugControllerImpl(
    transactionBlockService: GetTransactionBlockService[IO],
    blocksReader: BlocksReader[IO],
    blockchainConfig: BlockchainConfig,
    worldStateBuilder: WorldStateBuilder[IO],
    defaultTimeout: FiniteDuration
)(implicit current: CurrentBranch.Signal[IO], ap: Applicative[IO])
    extends DebugController[IO] {

  /** Replay the transaction specified in the request with tracing activated.
    * This may be stopped if in case of a timeout.
    */
  override def traceTransaction(request: TraceTransactionRequest): ServiceResponse[TraceTransactionResponse] = {
    val tracer = new JSTracer(request.traceParameters.tracer)
    traceTransaction(request.transactionHash, tracer, request.traceParameters.timeout.getOrElse(defaultTimeout))
  }

  /** Attempt to run the transaction in the exact same manner as it was executed by the network.
    */
  private def traceTransaction(
      txHash: TransactionHash,
      tracer: JSTracer,
      tracerTimeout: FiniteDuration
  ): ServiceResponse[TraceTransactionResponse] =
    getDataForTracer(txHash).flatMap {
      case Left(ex) => IO.pure(Left(ex))

      case Right((signedTx, sender, parentHeader, currentHeader, previousSignedTx)) =>
        // the starting point is the state of the parent block containing the tx being traced
        val blockContext = BlockContext.from(currentHeader)
        worldStateBuilder.getWorldStateForBlock(parentHeader.stateRoot, blockContext).use { worldState =>
          if (worldState.stateStorage.get(parentHeader.stateRoot).nonEmpty) {
            (for {
              traceState <- EitherT(rebuildState(previousSignedTx, worldState))
              traceRes <-
                EitherT(runTxWithTrace(blockContext, signedTx, sender, traceState.worldState, tracer, tracerTimeout))
            } yield TraceTransactionResponse(traceRes)).value
          } else
            IO.pure(
              Left(
                JsonRpcError.TracerException(show"Block state containing transaction with hash $txHash was not found.")
              )
            )
        }
    }

  private def getDataForTracer(
      txHash: TransactionHash
  ): IO[Either[JsonRpcError, (SignedTransaction, Address, ObftHeader, ObftHeader, Seq[SignedTransaction])]] =
    (for {
      CurrentBranch(stable, best) <- EitherT.liftF(CurrentBranch.get[IO])
      TransactionLocation(blockHash, txIndex) <-
        EitherT.fromOptionF(
          transactionBlockService.getTransactionLocation(stable, best.hash)(txHash),
          JsonRpcError.TransactionNotFound(txHash)
        )
      ObftBlock(currentHeader, body) <-
        EitherT.fromOptionF(blocksReader.getBlock(blockHash), JsonRpcError.BlockNotFound)
      parentBlock <- EitherT.fromOptionF(blocksReader.getBlock(currentHeader.parentHash), JsonRpcError.BlockNotFound)
      signedTransaction <-
        EitherT.fromOption(body.transactionList.lift(txIndex), JsonRpcError.TransactionNotFound(txHash))
      sender <- EitherT.fromOption(
                  SignedTransaction.getSender(signedTransaction)(blockchainConfig.chainId),
                  JsonRpcError.SenderNotFound(txHash)
                )
      previousSignedTx = body.transactionList.take(txIndex)
    } yield (signedTransaction, sender, parentBlock.header, currentHeader, previousSignedTx)).value

  private def rebuildState(
      tx: Seq[SignedTransaction],
      state: InMemoryWorldState
  ): IO[Either[JsonRpcError, ObftBlockExecution.BlockExecutionResult]] = {
    val transactionExecution = new TransactionExecutionImpl(
      EVM[WorldType, StorageType](),
      SignedTransactionValidatorImpl,
      new BeneficiaryRewardAddressProvider(),
      blockchainConfig
    )
    transactionExecution
      .executeTransactions(tx, state)
      .map(_.left.map { err: TxsExecutionError =>
        JsonRpcError.LogicError(show"Error while preparing state for tracer when executing block transactions: $err")
      })
  }

  private def runTxWithTrace(
      blockContext: BlockContext,
      signedTx: SignedTransaction,
      sender: Address,
      worldState: InMemoryWorldState,
      tracer: Tracer,
      timeout: FiniteDuration
  ): ServiceResponse[String] =
    runVM(signedTx, sender, blockContext, worldState, tracer, timeout).map { case (programContext, programResult) =>
      programResult.tracingError.fold {
        tracer
          .result(programContext, programResult)
          .left
          .map(tracingError => JsonRpcError.TracerException(tracingError.toString))
      }(error => Left(JsonRpcError.TracerException(error.toString)))
    }

  private def runVM(
      stx: SignedTransaction,
      senderAddress: Address,
      blockContext: BlockContext,
      world: InMemoryWorldState,
      tracer: Tracer,
      timeout: FiniteDuration
  ): IO[(PC, PR)] = {
    val vm          = EVM[WorldType, StorageType](tracer)
    val evmConfig   = EvmConfig.forBlock(blockContext.number, blockchainConfig)
    val context: PC = ProgramContext(stx.transaction, blockContext, senderAddress, world, evmConfig)

    IO.race(tracerTimeout(context, timeout), vm.run(context)).map(_.merge).map(result => (context, result))
  }

  private def tracerTimeout(context: PC, timeout: FiniteDuration): IO[ProgramResult[WorldType, StorageType]] =
    IO.sleep(timeout)
      .map(_ =>
        ProgramResult[WorldType, StorageType](
          returnData = ByteString.empty,
          gasRemaining = context.startGas,
          world = context.world,
          addressesToDelete = Set(),
          logs = Nil,
          internalTxs = Nil,
          gasRefund = 0,
          executionTime = Duration.Zero,
          error = None,
          tracingError = Some(
            TracingError(
              new RuntimeException(
                "Tracer timed out while processing request. You can provide a longer timeout setting."
              )
            )
          ),
          accessedAddresses = Set.empty,
          accessedStorageKeys = Set.empty
        )
      )
}
