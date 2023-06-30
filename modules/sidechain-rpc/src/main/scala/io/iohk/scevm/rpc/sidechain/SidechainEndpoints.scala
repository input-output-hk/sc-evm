package io.iohk.scevm.rpc.sidechain

import cats.syntax.all._
import cats.{Functor, Monad}
import io.iohk.armadillo.JsonRpcServerEndpoint
import io.iohk.scevm.rpc.armadillo.Endpoints
import io.iohk.scevm.rpc.sidechain.SidechainController.GetCandidatesRequest

object SidechainEndpoints extends Endpoints {

  def getSidechainEndpoints[F[_]: Monad](
      sidechainController: SidechainController[F],
      crossChainTransactionsController: CrossChainTransactionController[F]
  ): List[JsonRpcServerEndpoint[F]] =
    List(
      sidechain_getCurrentCandidates(sidechainController),
      sidechain_getCandidates(sidechainController),
      sidechain_getCommittee(sidechainController),
      sidechain_getEpochSignature(sidechainController),
      sidechain_getStatus(sidechainController),
      sidechain_getOutgoingTransactions(crossChainTransactionsController),
      sidechain_getPendingTransactions(crossChainTransactionsController),
      sidechain_getParams(sidechainController),
      sidechain_getOutgoingTxMerkleProof(sidechainController),
      sidechain_getSignaturesToUpload(sidechainController)
    )

  private def sidechain_getCurrentCandidates[F[_]: Monad](
      sidechainController: SidechainController[F]
  ): JsonRpcServerEndpoint[F] =
    SidechainApi.sidechain_getCurrentCandidates.serverLogic[F] { _ =>
      sidechainController.getCurrentCandidates.noDataErrorMapValue(_.candidates)
    }

  private def sidechain_getCandidates[F[_]: Monad](
      sidechainController: SidechainController[F]
  ): JsonRpcServerEndpoint[F] =
    SidechainApi.sidechain_getCandidates.serverLogic[F] { getCandidatesInput =>
      sidechainController.getCandidates(GetCandidatesRequest(getCandidatesInput)).noDataErrorMapValue(_.candidates)
    }

  private def sidechain_getCommittee[F[_]: Functor](
      sidechainController: SidechainController[F]
  ): JsonRpcServerEndpoint[F] =
    SidechainApi.sidechain_getCommittee.serverLogic[F] { epochParam =>
      sidechainController.getCommittee(epochParam).noDataError.value
    }

  private def sidechain_getEpochSignature[F[_]: Functor](
      sidechainController: SidechainController[F]
  ): JsonRpcServerEndpoint[F] =
    SidechainApi.sidechain_getEpochSignatures.serverLogic[F] { epochParam =>
      sidechainController.getEpochSignatures(epochParam).noDataError.value
    }

  private def sidechain_getStatus[F[_]: Functor](
      sidechainController: SidechainController[F]
  ): JsonRpcServerEndpoint[F] =
    SidechainApi.sidechain_getStatus.serverLogic[F] { _ =>
      sidechainController.getStatus().noDataError.value
    }

  private def sidechain_getOutgoingTransactions[F[_]: Functor](
      crossChainTransactionsController: CrossChainTransactionController[F]
  ): JsonRpcServerEndpoint[F] =
    SidechainApi.sidechain_getOutgoingTransactions.serverLogic[F] { epochParam =>
      crossChainTransactionsController.getOutgoingTransactions(epochParam).noDataError.value
    }

  private def sidechain_getPendingTransactions[F[_]: Functor](
      crossChainTransactionsController: CrossChainTransactionController[F]
  ): JsonRpcServerEndpoint[F] =
    SidechainApi.sidechain_getPendingTransactions.serverLogic[F] { _ =>
      crossChainTransactionsController.getPendingTransactions().noDataError.value
    }

  private def sidechain_getParams[F[_]: Functor](
      sidechainController: SidechainController[F]
  ): JsonRpcServerEndpoint[F] =
    SidechainApi.sidechain_getParams.serverLogic { _ =>
      sidechainController.getSidechainParams().map(_.asRight)
    }

  private def sidechain_getOutgoingTxMerkleProof[F[_]: Monad](
      sidechainController: SidechainController[F]
  ): JsonRpcServerEndpoint[F] =
    SidechainApi.sidechain_getOutgoingTxMerkleProof.serverLogic[F] { case (se, txId) =>
      sidechainController.getOutgoingTxMerkleProof(se, txId).noDataError.value
    }

  private def sidechain_getSignaturesToUpload[F[_]: Functor](
      sidechainController: SidechainController[F]
  ) = SidechainApi.sidechain_getSignaturesToUpload.serverLogic[F] { limit =>
    sidechainController.getSignaturesToUpload(limit).noDataError.value
  }
}
