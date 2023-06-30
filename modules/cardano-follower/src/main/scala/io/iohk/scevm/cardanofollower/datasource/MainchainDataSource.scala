package io.iohk.scevm.cardanofollower.datasource

import cats.data.NonEmptyList
import cats.{Show, derived}
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.scevm.domain.{MainchainPublicKey, MainchainSignature, SidechainPublicKey, SidechainSignatureNoRecovery}
import io.iohk.scevm.sidechain.IncomingCrossChainTransaction
import io.iohk.scevm.trustlesssidechain.cardano._

trait MainchainDataSource[F[_], CrossChainScheme <: AbstractSignatureScheme] {

  /** Returns data at the beginning of given epoch. */
  def getEpochData(epoch: MainchainEpoch): F[Option[EpochData[CrossChainScheme]]]

  def getMainchainData(slot: MainchainSlot): F[Option[EpochData[CrossChainScheme]]]

  def getLatestBlockInfo: F[Option[MainchainBlockInfo]]

  def getStableBlockInfo(currentSlot: MainchainSlot): F[Option[MainchainBlockInfo]]
}

trait IncomingTransactionDataSource[F[_]] {

  /** Returns incoming transactions that happened after `after` and were stable at the `at` main chain slot. */
  def getNewTransactions(after: Option[MainchainTxHash], at: MainchainSlot): F[List[IncomingCrossChainTransaction]]

  /** Returns incoming transactions that happened after `after` but before the `at` main chain slot. This does
    * apply any filter to the transactions.
    */
  def getUnstableTransactions(after: Option[MainchainTxHash], at: MainchainSlot): F[List[IncomingCrossChainTransaction]]

}

/** @param previousEpochNonce nonce of the previous epoch.
  *                           In the handover phase of the last sidechain epoch within an main chain epoch,
  *                           we ask for epoch data of future main chain epoch. However nonce should be known already
  *                           in the handover phase, DbSync implementation doesn't know it yet. For this reason we
  *                           are forced to use nonce of the previous epoch.
  * @param candidates leader candidates for the epoch.
  */
final case class EpochData[CrossChainScheme <: AbstractSignatureScheme](
    previousEpochNonce: EpochNonce,
    candidates: List[LeaderCandidate[CrossChainScheme]]
)

final case class ValidEpochData[Scheme <: AbstractSignatureScheme](
    previousEpochNonce: EpochNonce,
    candidates: Set[ValidLeaderCandidate[Scheme]]
)

object ValidEpochData {
  implicit def show[Scheme <: AbstractSignatureScheme]: Show[ValidEpochData[Scheme]] = cats.derived.semiauto.show
}

final case class LeaderCandidate[CrossChainScheme <: AbstractSignatureScheme](
    mainchainPubKey: MainchainPublicKey,
    registrations: NonEmptyList[RegistrationData[CrossChainScheme]]
) {
  override def toString: String = LeaderCandidate.show[CrossChainScheme].show(this)
}
object LeaderCandidate {
  implicit def show[CrossChainScheme <: AbstractSignatureScheme]: Show[LeaderCandidate[CrossChainScheme]] =
    cats.derived.semiauto.show
}

final case class RegistrationData[CrossChainScheme <: AbstractSignatureScheme](
    consumedInput: UtxoId,
    txInputs: List[UtxoId],
    sidechainSignature: SidechainSignatureNoRecovery,
    stakeDelegation: Lovelace,
    mainchainSignature: MainchainSignature,
    crossChainSignature: CrossChainScheme#SignatureWithoutRecovery,
    sidechainPubKey: SidechainPublicKey,
    crossChainPubKey: CrossChainScheme#PublicKey,
    utxoInfo: UtxoInfo,
    isPending: Boolean,
    pendingChange: Option[PendingRegistrationChange]
)

sealed trait PendingRegistrationChange {
  def txHash: MainchainTxHash
  def effectiveAt: MainchainEpoch
}
object PendingRegistrationChange {
  implicit val show: Show[PendingRegistrationChange] = cats.derived.semiauto.show
}
final case class PendingRegistration(txHash: MainchainTxHash, effectiveAt: MainchainEpoch)
    extends PendingRegistrationChange
final case class PendingDeregistration(txHash: MainchainTxHash, effectiveAt: MainchainEpoch)
    extends PendingRegistrationChange

object RegistrationData {
  implicit def show[CrossChainScheme <: AbstractSignatureScheme]: Show[RegistrationData[CrossChainScheme]] =
    cats.derived.semiauto.show
}

final case class ValidLeaderCandidate[Scheme <: AbstractSignatureScheme](
    pubKey: SidechainPublicKey,
    crossChainPubKey: Scheme#PublicKey,
    stakeDelegation: Lovelace
)

object ValidLeaderCandidate {
  implicit def show[Scheme <: AbstractSignatureScheme]: Show[ValidLeaderCandidate[Scheme]] = derived.semiauto.show
}
