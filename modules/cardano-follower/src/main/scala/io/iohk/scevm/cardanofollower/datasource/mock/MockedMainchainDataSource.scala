package io.iohk.scevm.cardanofollower.datasource.mock

import cats.data.NonEmptyList
import cats.effect.kernel.Sync
import io.bullet.borer.Cbor
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{AbstractSignatureScheme, EdDSA}
import io.iohk.scevm.cardanofollower.datasource
import io.iohk.scevm.cardanofollower.datasource.{
  DatasourceConfig,
  EpochData,
  IncomingTransactionDataSource,
  LeaderCandidate,
  MainchainDataSource,
  RegistrationData
}
import io.iohk.scevm.domain.{Address, MainchainPublicKey, SidechainPrivateKey, SidechainPublicKey}
import io.iohk.scevm.plutus.DatumEncoder
import io.iohk.scevm.sidechain.IncomingCrossChainTransaction
import io.iohk.scevm.trustlesssidechain.cardano._
import io.iohk.scevm.trustlesssidechain.{RegisterValidatorSignedMessage, SidechainParams}

class MockedMainchainDataSource[F[_]: Sync, CrossChainScheme <: AbstractSignatureScheme](
    sidechainParams: SidechainParams,
    mockConfig: DatasourceConfig.Mock,
    numberOfMainchainSlot: Int
) extends MainchainDataSource[F, CrossChainScheme]
    with IncomingTransactionDataSource[F] {

  private def candidate(
      mainchainPrvKey: EdDSA.PrivateKey,
      sidechainPrvKey: SidechainPrivateKey,
      consumedInput: UtxoId
  ): LeaderCandidate[CrossChainScheme] = {
    val mainchainPubKey: MainchainPublicKey = EdDSA.PublicKey.fromPrivate(mainchainPrvKey)
    val sidechainPubKey: SidechainPublicKey = SidechainPublicKey.fromPrivateKey(sidechainPrvKey)

    val message = {
      val msg = RegisterValidatorSignedMessage(sidechainParams, sidechainPubKey, consumedInput)
      ByteString(Cbor.encode(DatumEncoder[RegisterValidatorSignedMessage].encode(msg)).toByteArray)
    }

    LeaderCandidate(
      mainchainPubKey = mainchainPubKey,
      registrations = NonEmptyList.of(
        RegistrationData(
          consumedInput = consumedInput,
          List(consumedInput),
          sidechainSignature = sidechainPrvKey.sign(Blake2bHash32.hash(message).bytes).withoutRecoveryByte,
          crossChainSignature = sidechainPrvKey
            .sign(Blake2bHash32.hash(message).bytes)
            .withoutRecoveryByte
            .asInstanceOf[CrossChainScheme#SignatureWithoutRecovery],
          stakeDelegation = Lovelace(1001995478725L), // scalastyle:ignore magic.number
          mainchainSignature = mainchainPrvKey.sign(message),
          sidechainPubKey = sidechainPubKey,
          crossChainPubKey = sidechainPubKey.asInstanceOf[CrossChainScheme#PublicKey],
          utxoInfo = UtxoInfo(
            utxoId = UtxoId.parseUnsafe("abeed7fb0067f14d6f6436c7f7dedb27ce3ceb4d2d18ff249d43b22d86fae3f1#1"),
            epoch = MainchainEpoch(0),
            blockNumber = MainchainBlockNumber(0),
            slotNumber = MainchainSlot(0),
            txIndexWithinBlock = 0
          ),
          isPending = false,
          pendingChange = None
        )
      )
    )
  }

  private def getCandidate(indexInConf: Int): LeaderCandidate[CrossChainScheme] = {
    val candidateConfig = mockConfig.candidates(indexInConf)
    candidate(candidateConfig.mainchainPrvKey, candidateConfig.sidechainPrvKey, candidateConfig.consumedInput)
  }

  private val candidate1 = getCandidate(0)
  private val candidate2 = getCandidate(1)
  private val candidate3 = getCandidate(2)
  private val candidate4 = getCandidate(3)
  private val candidate5 = getCandidate(4)
  private val candidate6 = getCandidate(5)

  private lazy val epochData = {
    val data0 = EpochData(
      EpochNonce(0),
      List(candidate1, candidate2, candidate3, candidate4, candidate5, candidate6)
    )

    val data1 = datasource.EpochData(
      EpochNonce(1),
      List(candidate1)
    )

    val data2 = datasource.EpochData(
      EpochNonce(2),
      List(candidate1, candidate2, candidate3, candidate4, candidate5, candidate6)
    )

    Map(
      0L -> data0,
      1L -> data1,
      2L -> data2,
      3L -> data2,
      4L -> data2
    )
  }

  private val sidechainAddresses: List[Address] =
    List(candidate1, candidate2, candidate3, candidate4, candidate5, candidate6)
      .map(candidate => Address.fromPublicKey(candidate.registrations.head.sidechainPubKey))

  private val incomingTransactionGenerator =
    IncomingTransactionGeneratorImpl(numberOfMainchainSlot, mockConfig, sidechainAddresses)

  override def getNewTransactions(
      after: Option[MainchainTxHash],
      at: MainchainSlot
  ): F[List[IncomingCrossChainTransaction]] =
    Sync[F].pure(incomingTransactionGenerator.generateIncomingTransactions(after, at))

  override def getUnstableTransactions(
      after: Option[MainchainTxHash],
      at: MainchainSlot
  ): F[List[IncomingCrossChainTransaction]] = Sync[F].pure(List.empty)

  override def getEpochData(epoch: MainchainEpoch): F[Option[EpochData[CrossChainScheme]]] =
    Sync[F].pure(epochData.get(epoch.number))

  override def getMainchainData(slot: MainchainSlot): F[Option[EpochData[CrossChainScheme]]] =
    Sync[F].pure(epochData.get(slot.number))

  override def getLatestBlockInfo: F[Option[MainchainBlockInfo]] = ???

  override def getStableBlockInfo(currentSlot: MainchainSlot): F[Option[MainchainBlockInfo]] = ???
}
