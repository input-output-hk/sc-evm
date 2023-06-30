package io.iohk.scevm.sidechain

import cats.Functor
import cats.data.{Ior, NonEmptyList}
import com.softwaremill.diffx.generic.auto.diffForCaseClass
import com.softwaremill.diffx.scalatest.DiffShouldMatcher
import com.softwaremill.quicklens._
import io.bullet.borer.Cbor
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto._
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.cardanofollower.datasource
import io.iohk.scevm.cardanofollower.datasource.LeaderCandidate
import io.iohk.scevm.domain.{SidechainPrivateKey, SidechainPublicKey}
import io.iohk.scevm.plutus.DatumEncoder
import io.iohk.scevm.sidechain.committee.{CandidateRegistration, CandidateRegistrationValidatorImpl}
import io.iohk.scevm.sidechain.testing.SidechainGenerators
import io.iohk.scevm.testing.CryptoGenerators
import io.iohk.scevm.testing.CryptoGenerators.ecdsaKeyPairGen
import io.iohk.scevm.testing.Generators.bigIntGen
import io.iohk.scevm.trustlesssidechain.cardano._
import io.iohk.scevm.trustlesssidechain.{RegisterValidatorSignedMessage, SidechainParams}
import org.scalacheck.Gen
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.security.SecureRandom

class CandidateValidatorSpec extends AnyFreeSpec with Matchers with ScalaCheckPropertyChecks with DiffShouldMatcher {

  val txInput: UtxoId = UtxoId(
    MainchainTxHash(
      ByteString(Hex.decodeAsArrayUnsafe("cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13"))
    ),
    0
  )
  val sidechainParams: SidechainParams = SidechainFixtures.sidechainParams

  "candidates that have valid signatures should be valid" in {
    forAll(
      leaderCandidateGen[ECDSA](validSidechainSignature, validMainchainSignature, validSidechainSignatureShort[ECDSA])
    ) { candidate =>
      CandidateRegistrationValidatorImpl.validateCandidate(
        candidate,
        sidechainParams,
        Lovelace(0)
      ) shouldMatchTo CandidateRegistration.Active(
        candidate.registrations.head,
        candidate.mainchainPubKey,
        Nil,
        Nil,
        Nil
      )
    }
  }

  "candidates with invalid sidechain signature should be invalid" in {
    val (prvKey, pubKey) = ECDSA.generateKeyPair(new SecureRandom(BigInt("34567890456789").toByteArray))
    val invalidSignature =
      (_: Any, _: Any) => ECDSASignature.sign(ByteString(kec256(message(pubKey).toArray)), prvKey.bytes)
    forAll(
      leaderCandidateGen[ECDSA](
        invalidSignature,
        validMainchainSignature,
        invalidSignature(_, _).withoutRecoveryByte
      )
    ) { candidate =>
      val result = CandidateRegistrationValidatorImpl.validateCandidate(
        candidate,
        sidechainParams,
        Lovelace(0)
      )
      result match {
        case active: CandidateRegistration.Active[ECDSA] => fail(s"Expected invalid candidate, but got: $active")
        case CandidateRegistration.Inactive(_, inactive) =>
          inactive match {
            case Ior.Left(invalid) => assert(invalid.head.reasons.exists(_.startsWith("Invalid sidechain signature")))
            case _                 => fail("expected to have only invalid registration")
          }
      }
    }
  }

  "candidates with invalid mainchain signature should be invalid" in {
    val (eddsaPriv, _) = EdDSA.generateKeyPair(new SecureRandom(BigInt("34567890456789").toByteArray))
    val (_, ecdsaPub)  = ECDSA.generateKeyPair(new SecureRandom(BigInt("34567890456789").toByteArray))
    forAll(
      leaderCandidateGen[ECDSA](
        validSidechainSignature,
        (_: Any, _: Any) => eddsaPriv.sign(Blake2bHash28.hash(message(ecdsaPub)).bytes),
        validSidechainSignatureShort[ECDSA]
      )
    ) { candidate =>
      val result = CandidateRegistrationValidatorImpl.validateCandidate(
        candidate,
        sidechainParams,
        Lovelace(0)
      )

      result match {
        case active: CandidateRegistration.Active[ECDSA] => fail(s"Expected invalid candidate, but got: $active")
        case CandidateRegistration.Inactive(_, inactive) =>
          inactive match {
            case Ior.Left(invalid) => assert(invalid.head.reasons.exists(_.startsWith("Invalid mainchain signature")))
            case _                 => fail("expected to have only invalid registration")
          }
      }
    }
  }

  "should choose the last one for a candidate with multiple registrations" in {
    forAll(
      leaderCandidateGen[ECDSA](validSidechainSignature, validMainchainSignature, validSidechainSignatureShort[ECDSA])
    ) { candidate =>
      val initialRegistration = candidate.registrations.head
      val latestValidRegistration = initialRegistration.copy(utxoInfo =
        initialRegistration.utxoInfo.copy(blockNumber = MainchainBlockNumber(4), txIndexWithinBlock = 3)
      )
      val wrongKey = ECDSA.PublicKey.fromHexUnsafe(
        "8845f1af9553b32db01c44d2cce62d483f55910b613ecd41ddadbf328a028a1a" +
          "7d3f3073dde241824147f0af339917c7e1d5f8cb643e51ce1ef9576f9c16c06c"
      )
      val invalidRegistration = initialRegistration.copy(
        sidechainPubKey = wrongKey,
        utxoInfo = initialRegistration.utxoInfo.copy(blockNumber = MainchainBlockNumber(2), txIndexWithinBlock = 0)
      )
      val secondInvalidRegistration = initialRegistration.copy(
        sidechainPubKey = wrongKey,
        utxoInfo = initialRegistration.utxoInfo.copy(blockNumber = MainchainBlockNumber(1), txIndexWithinBlock = 0)
      )
      val thirdInvalidRegistration = initialRegistration.copy(
        sidechainPubKey = wrongKey,
        utxoInfo = initialRegistration.utxoInfo.copy(blockNumber = MainchainBlockNumber(4), txIndexWithinBlock = 0)
      )
      val registrations = NonEmptyList.of(
        invalidRegistration,
        latestValidRegistration,
        secondInvalidRegistration,
        thirdInvalidRegistration
      )

      val result =
        CandidateRegistrationValidatorImpl.validateCandidate(
          candidate.copy(registrations = registrations),
          sidechainParams,
          Lovelace(0)
        )
      result match {
        case CandidateRegistration.Active(
              data,
              _,
              supersededRegistrations,
              pendingRegistrations,
              invalidRegistrations
            ) =>
          assert(supersededRegistrations.isEmpty)
          assert(pendingRegistrations.isEmpty)
          assert(
            invalidRegistrations.map(_.registrationData).toSet == Set(
              invalidRegistration,
              secondInvalidRegistration,
              thirdInvalidRegistration
            )
          )
          assert(latestValidRegistration == data)
        case inactive: CandidateRegistration.Inactive[ECDSA] => fail(s"Expected active but got: $inactive")
      }
    }
  }

  "candidates that don't meet minimal stake requirement should not be valid" in {
    val minimalStakeThreshold = Lovelace(1000)
    forAll(
      leaderCandidateGen[ECDSA](validSidechainSignature, validMainchainSignature, validSidechainSignatureShort[ECDSA])
    ) { candidate =>
      CandidateRegistrationValidatorImpl.validateCandidate(
        candidate.modify(_.registrations.each.stakeDelegation).setTo(Lovelace(999)),
        sidechainParams,
        minimalStakeThreshold
      ) match {
        case _: CandidateRegistration.Active[ECDSA] => fail("expected registration to be inactive")
        case inactive: CandidateRegistration.Inactive[ECDSA] =>
          assert(inactive.invalid.head.reasons == NonEmptyList.one("Insufficient candidate stake: 999, required: 1000"))

      }
    }
  }

  "registrations that doesn't consume utxo from datum should not be valid" in {
    forAll(
      leaderCandidateGen[ECDSA](validSidechainSignature, validMainchainSignature, validSidechainSignatureShort[ECDSA]),
      Gen.listOf(SidechainGenerators.utxoIdGen)
    ) { case (candidate, utxoIds) =>
      val utxosInDatums    = candidate.registrations.map(_.consumedInput).toList
      val differentUtxoIds = utxoIds.filter(utxoId => utxosInDatums.contains(utxoId))
      CandidateRegistrationValidatorImpl.validateCandidate(
        candidate.modify(_.registrations.each.txInputs).setTo(differentUtxoIds),
        sidechainParams,
        Lovelace(0)
      ) match {
        case _: CandidateRegistration.Active[ECDSA] => fail("expected registration to be inactive")
        case inactive: CandidateRegistration.Inactive[ECDSA] =>
          assert(inactive.invalid.head.reasons.exists(_.contains(" hasn't consumed utxo indicated in the datum")))
      }
    }
  }

  private def leaderCandidateGen[CrossChainScheme <: AbstractSignatureScheme](
      sidechainSig: (ECDSA.PrivateKey, ECDSA.PublicKey) => ECDSASignature,
      mainchainSig: (EdDSA.PrivateKey, ECDSA.PublicKey) => EdDSA.Signature,
      crossChainSig: (ECDSA.PublicKey, CrossChainScheme#PrivateKey) => CrossChainScheme#SignatureWithoutRecovery
  )(implicit
      genCrossChainKeyPair: Gen[(CrossChainScheme#PrivateKey, CrossChainScheme#PublicKey)]
  ): Gen[LeaderCandidate[CrossChainScheme]] =
    for {
      bigInt               <- bigIntGen
      (ecdsaPrv, ecdsaPub) <- CryptoGenerators.ecdsaKeyPairGen
      (eddsaPriv, eddsaPub) = EdDSA.generateKeyPair(new SecureRandom(bigInt.toByteArray))
      (ccPrv, ccPub)       <- genCrossChainKeyPair
      ecdsaSignature        = sidechainSig(ecdsaPrv, ecdsaPub)
      eddsaSig              = mainchainSig(eddsaPriv, ecdsaPub)
      ccSig                 = crossChainSig(ecdsaPub, ccPrv)
      amount               <- Gen.posNum[Long]
      pendingChange        <- Gen.option(SidechainGenerators.pendingChangeGen)
    } yield LeaderCandidate[CrossChainScheme](
      eddsaPub,
      NonEmptyList.one(
        datasource.RegistrationData[CrossChainScheme](
          txInput,
          List(txInput),
          ecdsaSignature.withoutRecoveryByte,
          Lovelace(amount),
          eddsaSig,
          isPending = false,
          sidechainPubKey = ecdsaPub,
          utxoInfo = UtxoInfo(
            UtxoId(
              MainchainTxHash(
                ByteString(Hex.decodeAsArrayUnsafe("cdefe62b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13"))
              ),
              0
            ),
            MainchainEpoch(0),
            MainchainBlockNumber(0),
            MainchainSlot(0),
            0
          ),
          pendingChange = pendingChange,
          crossChainSignature = ccSig,
          crossChainPubKey = ccPub
        )
      )
    )

  private def validMainchainSignature(eddsaPriv: EdDSA.PrivateKey, sidechainPubKey: ECDSA.PublicKey) =
    eddsaPriv.sign(message(sidechainPubKey))

  private def message(sidechainPubKey: SidechainPublicKey): ByteString = {
    val message    = RegisterValidatorSignedMessage(sidechainParams, sidechainPubKey, txInput)
    val serialized = ByteString(Cbor.encode(DatumEncoder[RegisterValidatorSignedMessage].encode(message)).toByteArray)
    serialized
  }

  private def validSidechainSignature(ecdsaPrivateKey: SidechainPrivateKey, sidechainPubKey: SidechainPublicKey) =
    ecdsaPrivateKey.sign(Blake2bHash32.hash(message(sidechainPubKey)).bytes)

  private def validSidechainSignatureShort[Scheme <: AbstractSignatureScheme](
      validatorPubKey: SidechainPublicKey,
      crossChainPrvKey: Scheme#PrivateKey
  ): Scheme#SignatureWithoutRecovery =
    crossChainPrvKey.sign(Blake2bHash32.hash(message(validatorPubKey)).bytes).withoutRecovery

  implicit def quickLensFunctorFromCats[F[_]: Functor, A]: QuicklensFunctor[F, A] = new QuicklensFunctor[F, A] {
    override def map(fa: F[A])(f: A => A): F[A] = Functor[F].map(fa)(f)
  }
}
