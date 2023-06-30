package io.iohk.scevm.sidechain.committee

import cats.data.{Ior, NonEmptyList}
import cats.effect.IO
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignatureNoRecovery, EdDSA}
import io.iohk.scevm.cardanofollower.datasource.{LeaderCandidate, RegistrationData, ValidLeaderCandidate}
import io.iohk.scevm.sidechain.committee.WhitelistedCandidatedRegistrationValidatorSpec._
import io.iohk.scevm.testing.IOSupport
import io.iohk.scevm.trustlesssidechain.cardano._
import org.scalatest.EitherValues
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class WhitelistedCandidatedRegistrationValidatorSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with IOSupport
    with EitherValues
    with ScalaCheckPropertyChecks {

  "WhitelistedCandidatedRegistrationValidator.validate" should {
    "validate only candidates in the list" in {
      val candidates: List[LeaderCandidate[ECDSA]] = List(candidate1, candidate2)
      val validator =
        new WhitelistedCandidateRegistrationValidator[IO, ECDSA](
          alwaysSucceedValidation,
          Set(candidate1.mainchainPubKey)
        )

      alwaysSucceedValidation.validate(candidates).ioValue shouldBe Set(
        ValidLeaderCandidate(candidate1Key, candidate1CrossChainKey, candidate1.registrations.head.stakeDelegation),
        ValidLeaderCandidate(candidate2Key, candidate2CrossChainKey, candidate2.registrations.head.stakeDelegation)
      )

      validator.validate(candidates).ioValue shouldBe (Set(
        ValidLeaderCandidate(candidate1Key, candidate1CrossChainKey, candidate1.registrations.head.stakeDelegation)
      ))
    }
  }

  "WhitelistedCandidatedRegistrationValidator.validateCandidate" should {
    "validate only candidates in the list" in {
      val validator =
        new WhitelistedCandidateRegistrationValidator[IO, ECDSA](
          alwaysSucceedValidation,
          Set(candidate1.mainchainPubKey)
        )

      alwaysSucceedValidation.validateCandidate(candidate2) shouldBe a[CandidateRegistration.Active[_]]

      val result = validator.validateCandidate(candidate2)
      result shouldBe a[CandidateRegistration.Inactive[_]]
      result.invalid.head.reasons.toList should contain("The SPO is not allowed to participate in this sidechain")
    }

    "Keep an inactive but whitelisted candidate unchanged" in {
      val validator =
        new WhitelistedCandidateRegistrationValidator[IO, ECDSA](
          alwaysPendingValidation,
          Set(candidate1.mainchainPubKey)
        )

      val result = validator.validateCandidate(candidate1)
      result shouldBe a[CandidateRegistration.Inactive[_]]
      result.invalid.size shouldBe 0
      result.pending.size shouldBe 1
    }

    "Set Inactive candidates as invalid if they are Pending" in {
      val validator =
        new WhitelistedCandidateRegistrationValidator[IO, ECDSA](
          alwaysPendingValidation,
          Set(candidate1.mainchainPubKey)
        )

      alwaysPendingValidation.validateCandidate(candidate2).pending.size shouldBe 1

      val result = validator.validateCandidate(candidate2)
      result shouldBe a[CandidateRegistration.Inactive[_]]
      result.invalid.head.reasons.toList should contain("The SPO is not allowed to participate in this sidechain")
      result.pending shouldBe List.empty
    }
  }
}

object WhitelistedCandidatedRegistrationValidatorSpec {

  private val alwaysSucceedValidation = new CandidateRegistrationValidator[IO, ECDSA] {
    override def validate(candidates: List[LeaderCandidate[ECDSA]]): IO[Set[ValidLeaderCandidate[ECDSA]]] =
      IO.pure(
        candidates
          .map(reg =>
            ValidLeaderCandidate(
              reg.registrations.head.sidechainPubKey,
              reg.registrations.head.crossChainPubKey,
              reg.registrations.head.stakeDelegation
            )
          )
          .toSet
      )

    override def validateCandidate(leaderCandidate: LeaderCandidate[ECDSA]): CandidateRegistration[ECDSA] =
      CandidateRegistration.Active(
        data = leaderCandidate.registrations.head,
        mainchainPubKey = leaderCandidate.mainchainPubKey,
        superseded = Nil,
        pending = Nil,
        invalid = Nil
      )
  }

  private val alwaysPendingValidation = new CandidateRegistrationValidator[IO, ECDSA] {
    override def validate(candidates: List[LeaderCandidate[ECDSA]]): IO[Set[ValidLeaderCandidate[ECDSA]]] =
      IO.pure(Set.empty)

    override def validateCandidate(leaderCandidate: LeaderCandidate[ECDSA]): CandidateRegistration[ECDSA] =
      CandidateRegistration.Inactive(
        mainchainPubKey = leaderCandidate.mainchainPubKey,
        inactive = Ior.right(NonEmptyList.of(SingleRegistrationStatus.Pending(leaderCandidate.registrations.head)))
      )
  }

  private val candidate1Key: ECDSA.PublicKey = ECDSA.PublicKey.fromHexUnsafe(
    "5234df2d3ec47156a7af789e25c1e6352e0bbf0bbc6a5aace1c9f3fef91d29edee44fe98a62a95114f24fe81e117911ef439292dc60a3df455c619cd0531f12d"
  )

  private val candidate1CrossChainKey: ECDSA.PublicKey = ECDSA.PublicKey.fromHexUnsafe(
    "694f047e2d2343984f25ea658e1a62ca8e10571f8482626ec941203125667e1fa672c39d6cc5f07ed739a1d81a37bbb06134113c8393ccf9fd9ea093c2097e23"
  )
  private val candidate1 = LeaderCandidate[ECDSA](
    EdDSA.PublicKey.fromHexUnsafe("c115c2b35fd2c8faa9bb12bcad8f393bdc08343e9ed71528e2cce76c26d77658"),
    NonEmptyList.of(
      RegistrationData[ECDSA](
        consumedInput = UtxoId.parseUnsafe("11" * 32 + "#0"),
        txInputs = List(UtxoId.parseUnsafe("11" * 32 + "#0")),
        sidechainSignature = ECDSASignatureNoRecovery.fromHexUnsafe(
          "3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc6167425"
        ),
        crossChainSignature = ECDSASignatureNoRecovery.fromHexUnsafe(
          "3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc6167425"
        ),
        stakeDelegation = Lovelace(1000),
        mainchainSignature = EdDSA.Signature.fromHexUnsafe(
          "2fbb950b0a8af8ae3706dd62478b6406699aac50900d28ebf5aaf3ecaa54286e9316aec0b1751540ab44f1d86e7041bc5c6813972b99bfa375e36aee3c1fde0c"
        ),
        sidechainPubKey = candidate1Key,
        crossChainPubKey = candidate1CrossChainKey,
        utxoInfo = UtxoInfo(
          UtxoId.parseUnsafe("11" * 32 + "#0"),
          MainchainEpoch(0),
          MainchainBlockNumber(2),
          MainchainSlot(2),
          0
        ),
        isPending = false,
        pendingChange = None
      )
    )
  )

  private val candidate2Key: ECDSA.PublicKey = ECDSA.PublicKey.fromHexUnsafe(
    "e7e08d243eef5f63dac211b4438d7e16ac05339f90c28bbae556d26e9e1607ea75cea3d713e32e27633513f7811b57d0da27e3f2841286a56ab5cf144056eb3b"
  )

  private val candidate2CrossChainKey: ECDSA.PublicKey = ECDSA.PublicKey.fromHexUnsafe(
    "e7e08d243eef5f63dac211b4438d7e16ac05339f90c28bbae556d26e9e1607ea75cea3d713e32e27633513f7811b57d0da27e3f2841286a56ab5cf144056eb3b"
  )
  private val candidate2 = LeaderCandidate[ECDSA](
    EdDSA.PublicKey.fromHexUnsafe("bf43fd84d0aa4897942e320f40285a569752e90ac94c93a3390403849ff9455a"),
    NonEmptyList.of(
      RegistrationData[ECDSA](
        consumedInput = UtxoId.parseUnsafe("11" * 32 + "#0"),
        txInputs = List(UtxoId.parseUnsafe("11" * 32 + "#0")),
        sidechainSignature = ECDSASignatureNoRecovery.fromHexUnsafe(
          "3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc6167425"
        ),
        crossChainSignature = ECDSASignatureNoRecovery.fromHexUnsafe(
          "3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc6167425"
        ),
        stakeDelegation = Lovelace(1000),
        mainchainSignature = EdDSA.Signature.fromHexUnsafe(
          "2fbb950b0a8af8ae3706dd62478b6406699aac50900d28ebf5aaf3ecaa54286e9316aec0b1751540ab44f1d86e7041bc5c6813972b99bfa375e36aee3c1fde0c"
        ),
        sidechainPubKey = candidate2Key,
        crossChainPubKey = candidate2CrossChainKey,
        utxoInfo = UtxoInfo(
          UtxoId.parseUnsafe("11" * 32 + "#0"),
          MainchainEpoch(0),
          MainchainBlockNumber(2),
          MainchainSlot(2),
          0
        ),
        isPending = false,
        pendingChange = None
      )
    )
  )

}
