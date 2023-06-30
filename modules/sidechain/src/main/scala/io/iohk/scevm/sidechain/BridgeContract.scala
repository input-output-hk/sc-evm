package io.iohk.scevm.sidechain

import cats.Show
import cats.data.OptionT
import cats.effect.Sync
import cats.syntax.all._
import io.estatico.newtype.macros.newtype
import io.iohk.bytes.ByteString.eqForByteString
import io.iohk.bytes.{ByteString, FromBytes}
import io.iohk.ethereum.crypto.{AbstractSignatureScheme, AnySignature, ECDSASignature}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.EpochPhase._
import io.iohk.scevm.domain.{
  Address,
  EpochPhase,
  Nonce,
  Slot,
  Token,
  Transaction,
  TransactionLogEntry,
  TransactionType02,
  UInt256
}
import io.iohk.scevm.exec.vm._
import io.iohk.scevm.sidechain.BridgeContract._
import io.iohk.scevm.sidechain.CrossChainSignaturesService.BridgeContract.AddressWithSignature
import io.iohk.scevm.sidechain.ValidIncomingCrossChainTransaction
import io.iohk.scevm.sidechain.certificate.CheckpointSigner.SignedCheckpoint
import io.iohk.scevm.sidechain.certificate.MerkleRootSigner.SignedMerkleRootHash
import io.iohk.scevm.sidechain.certificate.MissingCertificatesResolver
import io.iohk.scevm.sidechain.transactions._
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.solidity.Bytes32.ByteStringOps
import io.iohk.scevm.solidity.SolidityAbiDecoder.AddressDecoder
import io.iohk.scevm.solidity._
import io.iohk.scevm.trustlesssidechain.cardano.MainchainTxHash
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.util.{Failure, Success, Try}

class BridgeContract[F[_]: Sync, SignatureScheme <: AbstractSignatureScheme](
    chainId: ChainId,
    bridgeContractAddress: Address,
    transactionSimulator: TransactionSimulator[F]
)(implicit signatureFromBytes: FromBytes[SignatureScheme#Signature])
    extends IncomingCrossChainTransactionsProviderImpl.BridgeContract[F]
    with IncomingTransactionsService.BridgeContract[F]
    with CrossChainSignaturesService.BridgeContract[F, SignatureScheme]
    with HandoverTransactionProvider.BridgeContract[F, SignatureScheme]
    with MerkleProofProvider.BridgeContract[F]
    with MissingCertificatesResolver.BridgeContract[F] {

  implicit private val logger: Logger[F] = Slf4jLogger.getLogger[F]

  override def createUnlockTransaction(
      mTx: ValidIncomingCrossChainTransaction,
      currentSlot: Slot
  ): F[SystemTransaction] = {
    val unlockTxPayload = BridgeContract.buildUnlockCallPayload(
      IncomingTransaction(UInt256(mTx.value.value), mTx.recipient, IncomingTransactionId.fromTxHash(mTx.txId))
    )
    val initialTransaction = buildTransaction(unlockTxPayload)

    estimateGas(initialTransaction)
      .map(estimate => toSystemTransaction(initialTransaction, estimate))
  }

  override def createSignTransaction(
      handoverSignature: SignatureScheme#Signature,
      checkpoint: Option[SignedCheckpoint],
      txsBatchMRH: Option[SignedMerkleRootHash[SignatureScheme#Signature]]
  ): F[SystemTransaction] = {
    val signTxPayload = BridgeContract.buildSignCallPayload(handoverSignature, checkpoint, txsBatchMRH)

    val initialTransaction = buildTransaction(signTxPayload)
    estimateGas(initialTransaction)
      .map(estimate => toSystemTransaction(initialTransaction, estimate))
  }
  def getLastProcessedIncomingTransaction: F[Option[IncomingTransactionId]] =
    readContractData[IncomingTransactionId](
      SolidityAbi.solidityCall(Methods.GetLastProcessedTransaction)
    )
      .map { decodedRawData =>
        Option.unless(decodedRawData == IncomingTransactionId(ByteString.empty))(decodedRawData)
      }

  def getTransactionsInBatch(batch: UInt256): F[Seq[OutgoingTransaction]] = {
    val payload = SolidityAbi.solidityCall(Methods.OutgoingTransactions, batch)
    readContractData[Seq[OutgoingTransaction]](payload)
  }

  def isIncomingTransactionHandled(
      incomingTransactionId: IncomingTransactionId
  ): F[Either[ProgramError, Boolean]] = {
    val payload = SolidityAbi.solidityCall(Methods.IncomingTransactionHandled, incomingTransactionId)
    for {
      programResult <- transactionSimulator.executeTx(buildTransaction(payload))
    } yield programResult.toEither
      .map(data => SolidityAbiDecoder[Tuple1[Boolean]].decode(data)._1)
      .left
      .map(_.error)
  }

  override def getHandoverSignatures(
      epoch: SidechainEpoch,
      validators: List[Address]
  ): F[List[AddressWithSignature[SignatureScheme#Signature]]] = {
    val payload =
      SolidityAbi.solidityCall[SidechainEpoch, Seq[Address]](Methods.GetHandoverSignatures, epoch, validators)
    readContractData[Seq[AddressWithSignature[SignatureScheme#Signature]]](payload).map(_.toList)
  }

  override def getOutgoingTransactionSignatures(
      epoch: SidechainEpoch,
      validators: List[Address]
  ): F[List[AddressWithSignature[SignatureScheme#Signature]]] = {
    val payload =
      SolidityAbi.solidityCall[SidechainEpoch, Seq[Address]](
        Methods.GetOutgoingTransactionSignatures,
        epoch,
        validators
      )
    readContractData[Seq[AddressWithSignature[SignatureScheme#Signature]]](payload).map(_.toList)
  }

  private def readContractData[T: SolidityAbiDecoder](payload: ByteString) =
    for {
      _             <- Logger[F].trace(show"Reading contract data using payload: ${Hex.toHexString(payload)}")
      programResult <- transactionSimulator.executeTx(buildTransaction(payload))
      data          <- programResult.toEither.left.map(e => new RuntimeException(show"$e")).liftTo
      _             <- Logger[F].trace(show"Raw contract data response: ${Hex.toHexString(data)}")
    } yield SolidityAbiDecoder[Tuple1[T]].decode(data)._1

  def currentEpoch: F[SidechainEpoch] =
    readContractData[UInt256](SolidityAbi.solidityCall(Methods.CurrentEpoch))
      .map(v => SidechainEpoch(v.toLong))

  def currentSlot: F[Slot] =
    readContractData[UInt256](SolidityAbi.solidityCall(Methods.CurrentSlot)).map(Slot(_))

  def epochPhase: F[EpochPhase] =
    readContractData[EpochPhase](SolidityAbi.solidityCall(Methods.EpochPhase))

  private def toSystemTransaction(initialTransaction: TransactionType02, estimate: BigInt) =
    SystemTransaction(
      chainId = ChainId(initialTransaction.chainId.toByte),
      gasLimit = estimate,
      receivingAddress = bridgeContractAddress,
      value = initialTransaction.value,
      payload = initialTransaction.payload
    )

  private def buildTransaction(payload: ByteString) =
    TransactionType02(
      chainId = chainId.value,
      nonce = Nonce.Zero,
      maxPriorityFeePerGas = 0,
      maxFeePerGas = 0,
      gasLimit = Int.MaxValue,
      receivingAddress = bridgeContractAddress,
      value = 0,
      payload = payload,
      accessList = Nil
    )

  private def estimateGas(
      transaction: Transaction
  ): F[BigInt] = {
    val sender = Address(0)
    transactionSimulator
      .estimateGas(
        transaction,
        senderAddress = sender,
        _.copy(coinbase = sender)
      )
      .flatMap {
        case Right(estimate) => estimate.pure
        case Left(e) =>
          Sync[F].raiseError(new RuntimeException(show"error while estimating gas: $e"))
      }
  }

  override def getPreviousMerkleRoot: F[Option[merkletree.RootHash]] =
    getPreviousMerkleRootChainEntry.map(_.map(_.rootHash))

  private def getPreviousMerkleRootChainEntry: F[Option[MerkleRootEntry.NewMerkleRoot]] =
    OptionT(
      readContractData[Option[MerkleRootEntry]](
        SolidityAbi.solidityCall(Methods.GetPreviousTxsBatchMRH)
      )
    ).collect { case e: MerkleRootEntry.NewMerkleRoot => e }.value

  def getConversionRate: F[UInt256] = readContractData[UInt256](SolidityAbi.solidityCall(Methods.GetConversionRate))

  def getTransactionsMerkleRootHash(
      epoch: SidechainEpoch
  ): F[Option[merkletree.RootHash]] =
    OptionT(getMerkleRootChainEntry(epoch)).map(_.rootHash).value

  def getMerkleRootChainEntry(
      epoch: SidechainEpoch
  ): F[Option[MerkleRootEntry.NewMerkleRoot]] =
    OptionT(getMerkleRoot(epoch)).collect { case e: MerkleRootEntry.NewMerkleRoot => e }.value

  def getCheckpointBlock(epoch: SidechainEpoch): F[Option[CheckpointBlock]] =
    readContractData[Option[CheckpointBlock]](SolidityAbi.solidityCall(Methods.GetCheckpointBlock, epoch))

  def getMerkleRoot(epoch: SidechainEpoch): F[Option[MerkleRootEntry]] =
    readContractData[Option[MerkleRootEntry]](
      SolidityAbi.solidityCall(Methods.GetMerkleRootChain, epoch)
    )

}

// scalastyle:off number.of.methods
object BridgeContract {
  import scala.language.implicitConversions

  object Methods {
    val OutgoingTransactions             = "outgoingTransactions"
    val IncomingTransactionHandled       = "incomingTransactionHandled"
    val Lock                             = "lock"
    val Unlock                           = "unlock"
    val GetLastProcessedTransaction      = "getLastProcessedIncomingTransaction"
    val Sign                             = "sign"
    val GetHandoverSignatures            = "getHandoverSignatures"
    val GetOutgoingTransactionSignatures = "getOutgoingTransactionSignatures"
    val CurrentEpoch                     = "currentEpoch"
    val CurrentSlot                      = "currentSlot"
    val EpochPhase                       = "epochPhase"
    val GetPreviousTxsBatchMRH           = "getPreviousTxsBatchMerkleRootChainEntry"
    val GetMerkleRootChain               = "getMerkleRootChainEntry"
    val GetCheckpointBlock               = "getCheckpointBlock"
    val GetConversionRate                = "TOKEN_CONVERSION_RATE"
  }

  def buildUnlockCallPayload(incomingTransaction: IncomingTransaction): ByteString =
    SolidityAbi.solidityCall(
      Methods.Unlock,
      incomingTransaction.txId,
      incomingTransaction.recipient,
      incomingTransaction.value
    )

  def buildSignCallPayload[Signature <: AnySignature](
      handoverSignature: Signature,
      checkpoint: Option[SignedCheckpoint] = None,
      txsBatchMRH: Option[SignedMerkleRootHash[Signature]] = None
  ): ByteString =
    SolidityAbi.solidityCall(
      Methods.Sign,
      handoverSignature,
      SignedEpochData(
        txsBatchMRH = txsBatchMRH.map(v => Bytes32(v.rootHash.value)),
        checkpointBlock = checkpoint.map(v => CheckpointBlock(Bytes32(v.hash.byteString), UInt256(v.number.value))),
        outgoingTransactionSignature = txsBatchMRH.map(_.signature),
        checkpointSignature = checkpoint.map(_.signature)
      )
    )

  def prepareState(bridgeContractAddress: Address)(
      state: InMemoryWorldState,
      currentEpoch: SidechainEpoch,
      epochPhase: EpochPhase
  ): InMemoryWorldState = {
    val bridgeStorage = state.getStorage(bridgeContractAddress)
    val newBridgeStorage = bridgeStorage
      .store(BridgeContract.MemorySlots.Epoch, currentEpoch.number)
      .store(BridgeContract.MemorySlots.Slot, state.blockContext.slotNumber.number)
      .store(BridgeContract.MemorySlots.EpochPhase, epochPhaseToUint8(epochPhase).value)

    state.saveStorage(bridgeContractAddress, newBridgeStorage)
  }

  implicit val TokenDecoder: SolidityAbiDecoder[Token]                          = SolidityAbiDecoder[UInt256].map(Token(_))
  implicit val MainchainAddressDecoder: SolidityAbiDecoder[OutgoingTxRecipient] = OutgoingTxRecipient.deriving
  implicit val LongDecoder: SolidityAbiDecoder[Long]                            = SolidityAbiDecoder[UInt256].map(_.toLong)
  implicit val OutgoingTransactionIdDecoder: SolidityAbiDecoder[OutgoingTxId]   = OutgoingTxId.deriving
  implicit val OutgoingTransactionDecoder: SolidityAbiDecoder[OutgoingTransaction] =
    SolidityAbiDecoder.from((OutgoingTransaction.apply _).tupled)

  @newtype final case class IncomingTransactionId(bytes: ByteString) {
    def toTxHash: Either[String, MainchainTxHash] = {
      val txHashBytesLength = 32
      Either.cond(
        bytes.length == txHashBytesLength,
        MainchainTxHash(bytes.take(txHashBytesLength)),
        s"Invalid utxo bytes length. Expected $txHashBytesLength, but got ${bytes.length}"
      )
    }
  }
  object IncomingTransactionId {
    def fromTxHash(txHash: MainchainTxHash): IncomingTransactionId =
      IncomingTransactionId(txHash.value)
  }

  implicit val IncomingTransactionIdEncoder: SolidityAbiEncoder[IncomingTransactionId] =
    SolidityAbiEncoder[ByteString].contramap[IncomingTransactionId](_.bytes)
  implicit val IncomingTransactionIdDecoder: SolidityAbiDecoder[IncomingTransactionId] =
    SolidityAbiDecoder[ByteString].map(IncomingTransactionId.apply)

  final case class IncomingTransaction(value: UInt256, recipient: Address, txId: IncomingTransactionId)
  implicit val IncomingTransactionEncoder: SolidityAbiEncoder[IncomingTransaction] =
    SolidityAbiEncoder[(UInt256, Address, IncomingTransactionId)].contramap(IncomingTransaction.unapply(_).get)
  implicit val IncomingTransactionDecoder: SolidityAbiDecoder[IncomingTransaction] =
    SolidityAbiDecoder.from((IncomingTransaction.apply _).tupled)

  sealed trait IncomingTransactionResult
  object IncomingTransactionResult {
    case object Success                extends IncomingTransactionResult
    case object ErrorContractRecipient extends IncomingTransactionResult

    def toUint8(res: IncomingTransactionResult): UInt8 = UInt8(res match {
      case Success                => 0
      case ErrorContractRecipient => 1
    })

    def fromUint8(res: UInt8): Option[IncomingTransactionResult] =
      res.value.toInt match {
        case 0 => Some(Success)
        case 1 => Some(ErrorContractRecipient)
        case _ => None
      }
  }

  final case class IncomingTransactionHandledEvent(
      txId: IncomingTransactionId,
      recipient: Address,
      amount: UInt256,
      result: IncomingTransactionResult
  )

  object IncomingTransactionHandledEvent {
    def decodeFromLogEntry(e: TransactionLogEntry): IncomingTransactionHandledEvent = {
      val (txId, amount, result) =
        SolidityAbiDecoder[(IncomingTransactionId, UInt256, IncomingTransactionResult)].decode(e.data)
      val indexedRecipient = e.logTopics
        .get(1)
        .getOrElse(throw new RuntimeException("Missing indexed field recipient topic in transaction log entry"))
      IncomingTransactionHandledEvent(
        txId,
        Address(indexedRecipient),
        amount,
        result
      )
    }
  }
  def epochPhaseFromUint8(res: UInt8): Try[EpochPhase] =
    res.value.toInt match {
      case 0 => Success(Regular)
      case 1 => Success(ClosedTransactionBatch)
      case 2 => Success(Handover)
      case _ => Failure(new IllegalArgumentException(show"Cannot decode $res to EpochPhase"))
    }

  def epochPhaseToUint8(res: EpochPhase): UInt8 = UInt8(res match {
    case Regular                => 0
    case ClosedTransactionBatch => 1
    case Handover               => 2
  })

  implicit val IncomingTransactionResultEncoder: SolidityAbiEncoder[IncomingTransactionResult] =
    SolidityAbiEncoder[UInt8].contramap(IncomingTransactionResult.toUint8)
  implicit val IncomingTransactionHandledEventEncoder: SolidityAbiEncoder[IncomingTransactionHandledEvent] =
    SolidityAbiEncoder[(IncomingTransactionId, UInt256, IncomingTransactionResult)]
      .contramap(event => (event.txId, event.amount, event.result))

  implicit val IncomingTransactionResultDecoder: SolidityAbiDecoder[IncomingTransactionResult] =
    SolidityAbiDecoder[UInt8].map(IncomingTransactionResult.fromUint8(_).get)

  implicit val EpochPhaseDecoder: SolidityAbiDecoder[EpochPhase] =
    SolidityAbiDecoder[UInt8].map(epochPhaseFromUint8(_).get)
  implicit val EpochPhaseEncoder: SolidityAbiEncoder[EpochPhase] =
    SolidityAbiEncoder[UInt8].contramap(epochPhaseToUint8)

  final case class HandoverSignedEvent[Scheme <: AbstractSignatureScheme](
      validator: Address,
      signature: Scheme#Signature,
      epoch: SidechainEpoch
  )
  final case class OutgoingTransactionsSignedEvent[Scheme <: AbstractSignatureScheme](
      validator: Address,
      signature: Scheme#Signature,
      merkleRootHash: RootHash
  )

  final case class CheckpointBlockSignedEvent(
      validator: Address,
      signature: ECDSASignature,
      block: CheckpointBlock
  )

  final case class CheckpointBlock(hash: Bytes32, number: UInt256)

  object CheckpointBlock {
    val Empty: CheckpointBlock = CheckpointBlock(Bytes32.Zeros, 0)
  }
  final case class SignedEpochData[Signature](
      txsBatchMRH: Option[Bytes32],
      checkpointBlock: Option[CheckpointBlock],
      outgoingTransactionSignature: Option[Signature],
      checkpointSignature: Option[ECDSASignature]
  )

  implicit def abstractSignatureEncoder[T <: AnySignature]: SolidityAbiEncoder[T] =
    SolidityAbiEncoder[ByteString].contramap(_.toBytes)
  implicit val EcdsaSignatureDecoder: SolidityAbiDecoder[ECDSASignature] =
    SolidityAbiDecoder[ByteString].map(ECDSASignature.fromBytesUnsafe)
  implicit val SidechainEpochEncoder: SolidityAbiEncoder[SidechainEpoch] =
    SolidityAbiEncoder[UInt256].contramap(se => UInt256(se.number))
  implicit val SidechainEpochDecoder: SolidityAbiDecoder[SidechainEpoch] =
    SolidityAbiDecoder[UInt256].map(_.toLong).map(SidechainEpoch.apply)
  implicit def HandoverSignedEventEncoder[Scheme <: AbstractSignatureScheme](implicit
      signatureDecoder: SolidityAbiDecoder[Scheme#Signature]
  ): SolidityAbiEncoder[HandoverSignedEvent[Scheme]] =
    SolidityAbiEncoder[(Address, Scheme#Signature, SidechainEpoch)].contramap(HandoverSignedEvent.unapply(_).get)
  implicit def HandoverSignedEventDecoder[Scheme <: AbstractSignatureScheme](implicit
      signatureFromBytes: FromBytes[Scheme#Signature]
  ): SolidityAbiDecoder[HandoverSignedEvent[Scheme]] =
    SolidityAbiDecoder[(Address, ByteString, SidechainEpoch)].map { case (address, signature, epoch) =>
      (HandoverSignedEvent.apply[Scheme](address, signatureFromBytes(signature), epoch))
    }

  implicit val MerkleRootHashDecoder: SolidityAbiDecoder[merkletree.RootHash] =
    SolidityAbiDecoder[Bytes32].map(bs => merkletree.RootHash(bs.value))
  implicit def OutgoingTransactionsSignedEventDecoder[Scheme <: AbstractSignatureScheme](implicit
      signatureFromBytes: FromBytes[Scheme#Signature]
  ): SolidityAbiDecoder[OutgoingTransactionsSignedEvent[Scheme]] =
    SolidityAbiDecoder[(Address, ByteString, RootHash)].map { case (address, signature, rootHash) =>
      (OutgoingTransactionsSignedEvent.apply[Scheme](address, signatureFromBytes(signature), rootHash))
    }

  implicit def AddressWithSignatureEncoder[CrossChainSignature: SolidityAbiEncoder]
      : SolidityAbiEncoder[AddressWithSignature[CrossChainSignature]] =
    SolidityAbiEncoder.to(AddressWithSignature.unapply(_).get)
  implicit def AddressWithSignatureDecoder[CrossChainSignature](implicit
      fromBytes: FromBytes[CrossChainSignature]
  ): SolidityAbiDecoder[AddressWithSignature[CrossChainSignature]] =
    SolidityAbiDecoder.from[(Address, ByteString), AddressWithSignature[CrossChainSignature]] {
      case (address, signatureBytes) => AddressWithSignature(address, fromBytes(signatureBytes))
    }

  private lazy val defaultSolidityBytes32 = Bytes32(ByteString.fromArray(Array.fill(32)(0.toByte)))
  implicit val MerkleRootHashOptionEncoder: SolidityAbiEncoder[Option[merkletree.RootHash]] =
    SolidityAbiEncoder[Bytes32].contramap[Option[merkletree.RootHash]](
      _.fold(defaultSolidityBytes32)(_.value.asBytes32)
    )
  implicit val MerkleRootHashOptionDecoder: SolidityAbiDecoder[Option[merkletree.RootHash]] =
    SolidityAbiDecoder[Bytes32].map(bs => Option.when(bs != defaultSolidityBytes32)(merkletree.RootHash(bs.value)))

  implicit val CheckpointBlockEncoder: SolidityAbiEncoder[CheckpointBlock] =
    SolidityAbiEncoder.to(CheckpointBlock.unapply(_).get)
  implicit val CheckpointBlockDecoder: SolidityAbiDecoder[CheckpointBlock] =
    SolidityAbiDecoder.from((CheckpointBlock.apply _).tupled)
  implicit val OptionalCheckpointBlockDecoder: SolidityAbiDecoder[Option[CheckpointBlock]] =
    CheckpointBlockDecoder.map(b => if (b.hash == defaultSolidityBytes32) None else Some(b))
  implicit def SignedEpochDataEncoder[T <: AnySignature]: SolidityAbiEncoder[SignedEpochData[T]] =
    SolidityAbiEncoder[(Bytes32, CheckpointBlock, ByteString, ByteString)].contramap[SignedEpochData[T]] { epochData =>
      (
        epochData.txsBatchMRH.getOrElse(Bytes32.Zeros),
        epochData.checkpointBlock.getOrElse(CheckpointBlock.Empty),
        epochData.outgoingTransactionSignature.fold(ByteString.empty)(_.toBytes),
        epochData.checkpointSignature.fold(ByteString.empty)(s => s.toBytes)
      )
    }

  implicit val CheckpointBlockSignedEventDecoder: SolidityAbiDecoder[CheckpointBlockSignedEvent] =
    SolidityAbiDecoder[(Address, ECDSASignature, CheckpointBlock)].map(CheckpointBlockSignedEvent.tupled)

  /** Default log event topic is a keccak256 hash of the event signature, so it has to match definition from
    * bridge solidity contract.
    */
  val IncomingTransactionHandledEventTopic: ByteString =
    SolidityAbi.defaultEventTopic[ByteString, Address, UInt256, UInt8]("IncomingTransactionHandledEvent")

  val HandoverSignedEventTopic: ByteString =
    SolidityAbi.defaultEventTopic[Address, ByteString, UInt256]("HandoverSignedEvent")

  val CheckpointSignedEventTopic: ByteString =
    SolidityAbi.defaultEventTopic[Address, ECDSASignature, CheckpointBlock]("CheckpointBlockSignedEvent")

  val OutgoingTransactionsSignedEventTopic: ByteString =
    SolidityAbi.defaultEventTopic[Address, ByteString, Bytes32]("OutgoingTransactionsSignedEvent")

  sealed trait MerkleRootEntry

  object MerkleRootEntry {
    implicit val show: Show[MerkleRootEntry] = cats.derived.semiauto.show

    final case class NewMerkleRoot(
        rootHash: RootHash,
        previousEntryEpoch: Option[SidechainEpoch]
    ) extends MerkleRootEntry

    object NewMerkleRoot {
      implicit val show: Show[NewMerkleRoot] = cats.derived.semiauto.show
    }

    final case class PreviousRootReference(
        previousEntryEpoch: SidechainEpoch
    ) extends MerkleRootEntry

    object PreviousRootReference {
      implicit val show: Show[PreviousRootReference] = cats.derived.semiauto.show
    }
  }

  implicit val MerkleRootChainEntryOptionDecoder: SolidityAbiDecoder[Option[MerkleRootEntry]] =
    SolidityAbiDecoder[(RootHash, SidechainEpoch)].map {
      case (rh, previousEpoch) if rh.value === defaultSolidityBytes32.value && previousEpoch.number === 0 =>
        None // there is no chain entry in the contract; can happen when we are not yet in the handover phase for the given epoch
      case (rh, previousEpoch) if rh.value === defaultSolidityBytes32.value && previousEpoch.number === Long.MaxValue =>
        None // there is no merkle root and there never was one
      case (rh, previousEpoch) if rh.value =!= defaultSolidityBytes32.value && previousEpoch.number === Long.MaxValue =>
        Some(
          MerkleRootEntry.NewMerkleRoot(rh, None)
        ) // there is an entry and that is the first root in the history
      case (rh, previousEpoch) if rh.value === defaultSolidityBytes32.value && previousEpoch.number =!= Long.MaxValue =>
        Some(MerkleRootEntry.PreviousRootReference(previousEpoch))
      case (rh, previousEpoch) =>
        Some(
          MerkleRootEntry.NewMerkleRoot(rh, Some(previousEpoch))
        ) // there is an entry and it has a reference to the previous one
    }

  implicit def OutgoingTransactionsSignedEventShow[Scheme <: AbstractSignatureScheme]
      : Show[OutgoingTransactionsSignedEvent[Scheme]] = cats.derived.semiauto.show

  def isIncomingTransactionHandledEvent(bridgeAddress: Address, e: TransactionLogEntry): Boolean =
    e.loggerAddress == bridgeAddress && e.logTopics.contains(IncomingTransactionHandledEventTopic)

  def isHandoverSignedEvent(bridgeAddress: Address, e: TransactionLogEntry): Boolean =
    e.loggerAddress == bridgeAddress && e.logTopics.contains(HandoverSignedEventTopic)

  def isCheckpointSignedEvent(bridgeAddress: Address, e: TransactionLogEntry): Boolean =
    e.loggerAddress == bridgeAddress && e.logTopics.contains(CheckpointSignedEventTopic)

  def isOutgoingTransactionsSigned(bridgeAddress: Address, e: TransactionLogEntry): Boolean =
    e.loggerAddress == bridgeAddress && e.logTopics.contains(OutgoingTransactionsSignedEventTopic)

  object MemorySlots {
    val Epoch      = 0
    val Slot       = 1
    val EpochPhase = 2
  }

}
// scalastyle:on
