package io.iohk.scevm.sidechain

import cats.data.{Ior, NonEmptyList}
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.scevm.cardanofollower.datasource.RegistrationData
import io.iohk.scevm.domain.MainchainPublicKey
import io.iohk.scevm.sidechain.committee.SingleRegistrationStatus.{Invalid, Pending, Superseded}

package object committee {

  sealed trait CandidateRegistration[CrossChainScheme <: AbstractSignatureScheme] {
    def pending: List[Pending[CrossChainScheme]]
    def invalid: List[Invalid[CrossChainScheme]]
  }

  object CandidateRegistration {
    final case class Active[CrossChainScheme <: AbstractSignatureScheme](
        data: RegistrationData[CrossChainScheme],
        mainchainPubKey: MainchainPublicKey,
        superseded: List[Superseded[CrossChainScheme]],
        pending: List[Pending[CrossChainScheme]],
        invalid: List[Invalid[CrossChainScheme]]
    ) extends CandidateRegistration[CrossChainScheme]

    final case class Inactive[CrossChainScheme <: AbstractSignatureScheme](
        mainchainPubKey: MainchainPublicKey,
        inactive: Ior[NonEmptyList[Invalid[CrossChainScheme]], NonEmptyList[Pending[CrossChainScheme]]]
    ) extends CandidateRegistration[CrossChainScheme] {
      val pending: List[Pending[CrossChainScheme]] = inactive.right.map(_.toList).getOrElse(List.empty)
      val invalid: List[Invalid[CrossChainScheme]] = inactive.left.map(_.toList).getOrElse(List.empty)
    }

  }

  sealed trait SingleRegistrationStatus[CrossChainScheme <: AbstractSignatureScheme] {
    def registrationData: RegistrationData[CrossChainScheme]
  }
  object SingleRegistrationStatus {
    final case class Invalid[CrossChainScheme <: AbstractSignatureScheme](
        registrationData: RegistrationData[CrossChainScheme],
        reasons: NonEmptyList[String]
    ) extends SingleRegistrationStatus[CrossChainScheme]
    final case class Superseded[CrossChainScheme <: AbstractSignatureScheme](
        registrationData: RegistrationData[CrossChainScheme]
    ) extends SingleRegistrationStatus[CrossChainScheme]
    final case class Pending[CrossChainScheme <: AbstractSignatureScheme](
        registrationData: RegistrationData[CrossChainScheme]
    ) extends SingleRegistrationStatus[CrossChainScheme]
  }
}
