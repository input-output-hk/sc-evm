package io.iohk.scevm.sidechain.testing

import cats.data.NonEmptyVector
import io.iohk.ethereum.crypto.{AbstractSignatureScheme, ECDSA}
import io.iohk.scevm.cardanofollower.datasource.{
  PendingDeregistration,
  PendingRegistration,
  PendingRegistrationChange,
  ValidLeaderCandidate
}
import io.iohk.scevm.cardanofollower.testing.CardanoFollowerGenerators._
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.Token
import io.iohk.scevm.sidechain.ValidIncomingCrossChainTransaction
import io.iohk.scevm.sidechain.transactions._
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.testing.CryptoGenerators.ecdsaKeyPairGen
import io.iohk.scevm.testing.{BlockGenerators, Generators}
import io.iohk.scevm.trustlesssidechain.SidechainParams
import io.iohk.scevm.trustlesssidechain.cardano._
import org.scalacheck.Gen

object SidechainGenerators {

  val utxoIdGen: Gen[UtxoId] = for {
    hashBytes <- mainchainTxHashGen
    idx       <- Gen.choose(0, 100000)
  } yield UtxoId(hashBytes, idx)

  val validIncomingCrossChainTransactionGen: Gen[ValidIncomingCrossChainTransaction] = for {
    recipient        <- Generators.addressGen
    value            <- Generators.tokenGen
    txId             <- mainchainTxHashGen
    processedAtBlock <- mainchainBlockNumberGen
  } yield ValidIncomingCrossChainTransaction(recipient, value, txId, processedAtBlock)

  def validLeaderCandidateGen[Schema <: AbstractSignatureScheme](implicit
      genCrossChainPair: Gen[(Schema#PrivateKey, Schema#PublicKey)]
  ): Gen[ValidLeaderCandidate[Schema]] =
    committeeGenerator[Schema](1).map(_.head)

  def committeeGenerator[Schema <: AbstractSignatureScheme](size: Int)(implicit
      genCrossChainPair: Gen[(Schema#PrivateKey, Schema#PublicKey)]
  ): Gen[NonEmptyVector[ValidLeaderCandidate[Schema]]] =
    committeeGeneratorWithPrivateKeys[Schema](size).map(_.map(_._1))

  def committeeGeneratorWithPrivateKeys[Schema <: AbstractSignatureScheme](
      size: Int
  )(implicit
      genCrossChainPair: Gen[(Schema#PrivateKey, Schema#PublicKey)]
  ): Gen[NonEmptyVector[(ValidLeaderCandidate[Schema], Schema#PrivateKey)]] =
    for {
      keySet           <- Generators.genSetOfN[(ECDSA.PrivateKey, ECDSA.PublicKey)](size, ecdsaKeyPairGen)
      crossChainKeySet <- Generators.genSetOfN[(Schema#PrivateKey, Schema#PublicKey)](size, genCrossChainPair)
      stakes           <- Gen.containerOfN[List, Long](size, Gen.posNum[Long])
    } yield {
      val candidates = keySet.zip(stakes).zip(crossChainKeySet).map {
        case (((_, pubKey), amount), (crossChainPrvKey, crossChainPubKey)) =>
          (ValidLeaderCandidate[Schema](pubKey, crossChainPubKey, Lovelace(amount)), crossChainPrvKey)
      }
      NonEmptyVector.fromVectorUnsafe(candidates.toVector)
    }

  def validLeaderCandidateWithPrivateKeyGen[Schema <: AbstractSignatureScheme](implicit
      genCrossChainPair: Gen[(Schema#PrivateKey, Schema#PublicKey)]
  ): Gen[(ValidLeaderCandidate[Schema], Schema#PrivateKey)] =
    committeeGeneratorWithPrivateKeys[Schema](1).map(_.head)

  val outgoingTransactionGen: Gen[OutgoingTransaction] =
    for {
      value     <- Gen.long
      index     <- Gen.long
      recipient <- Generators.byteStringOfLengthNGen(32)
    } yield OutgoingTransaction(Token(value), OutgoingTxRecipient(recipient), OutgoingTxId(index))

  val chainIdGen: Gen[ChainId] = Gen.choose[Byte](0, Byte.MaxValue).map(ChainId.apply)

  val sidechainParamsGen: Gen[SidechainParams] = for {
    chainId     <- chainIdGen
    genesisHash <- BlockGenerators.blockHashGen
    genesisUtxo <- SidechainGenerators.utxoIdGen
    numerator   <- Gen.choose(1, 3)
    denominator <- Gen.choose(numerator, numerator + 2)
  } yield SidechainParams(chainId, genesisHash, genesisUtxo, numerator, denominator)

  def pendingChangeGen: Gen[PendingRegistrationChange] =
    for {
      txHash <- mainchainTxHashGen
      slot   <- Gen.choose(1, 50000)
      change <- Gen.oneOf(
                  PendingDeregistration(txHash, MainchainEpoch(slot)),
                  PendingRegistration(txHash, MainchainEpoch(slot))
                )
    } yield change

  def rootHashGen: Gen[RootHash] = Generators.byteStringOfLengthNGen(32).map(RootHash(_))
}
