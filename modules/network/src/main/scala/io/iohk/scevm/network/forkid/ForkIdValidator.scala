package io.iohk.scevm.network.forkid

import cats.Monad
import cats.data.EitherT._
import cats.effect.Sync
import cats.syntax.all._
import io.iohk.ethereum.utils.ByteUtils
import io.iohk.scevm.config.BlockchainConfig
import io.iohk.scevm.domain.{BlockHash, BlockNumber}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.{Logger, SelfAwareStructuredLogger}

import java.util.zip.CRC32

sealed trait ForkIdValidationResult
case object Connect                     extends ForkIdValidationResult
case object ErrRemoteStale              extends ForkIdValidationResult
case object ErrLocalIncompatibleOrStale extends ForkIdValidationResult

object ForkIdValidator {
  private[forkid] val MaxBlockNumber: BlockNumber = BlockNumber(
    // max uint64
    (BigInt(0x7fffffffffffffffL) << 1) + 1 // scalastyle:ignore magic.number
  )

  /** Tells whether it makes sense to connect to a peer or gives a reason why it isn't a good idea.
    *
    *  @param genesisHash - hash of the genesis block of the current chain
    *  @param config - local client's blockchain configuration
    *  @param currentHeight - number of the block at the current tip
    *  @param remoteForkId - ForkId announced by the connecting peer
    *  @return One of:
    *         - [[io.iohk.scevm.network.forkid.Connect]] - It is safe to connect to the peer
    *         - [[io.iohk.scevm.network.forkid.ErrRemoteStale]]  - Remote is stale, don't connect
    *         - [[io.iohk.scevm.network.forkid.ErrLocalIncompatibleOrStale]] - Local is incompatible or stale, don't connect
    */
  def validatePeer[F[_]: Sync](
      genesisHash: BlockHash,
      config: BlockchainConfig
  )(currentHeight: BlockNumber, remoteForkId: ForkId): F[ForkIdValidationResult] = {
    implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
    val forks                                         = ForkId.gatherForks(config)
    validatePeer[F](genesisHash, forks)(currentHeight, remoteForkId)
  }

  private[forkid] def validatePeer[F[_]: Monad: Logger](
      genesisHash: BlockHash,
      forks: List[BlockNumber]
  )(currentHeight: BlockNumber, remoteId: ForkId): F[ForkIdValidationResult] = {
    val checksums: Vector[BigInt] = calculateChecksums(genesisHash, forks)

    // find the first unpassed fork and its index
    val (unpassedFork, unpassedForkIndex) =
      forks.zipWithIndex.find { case (fork, _) => currentHeight < fork }.getOrElse((MaxBlockNumber, forks.length))

    // The checks are left biased -> whenever a result is found we need to short circuit
    val validate = (for {
      _ <- liftF(Logger[F].trace(s"Before checkMatchingHashes"))
      matching <-
        fromEither[F](
          checkMatchingHashes(checksums(unpassedForkIndex), remoteId, currentHeight).toLeft("hashes didn't match")
        )
      _   <- liftF(Logger[F].trace(s"checkMatchingHashes result: $matching"))
      _   <- liftF(Logger[F].trace(s"Before checkSubset"))
      sub <- fromEither[F](checkSubset(checksums, forks, remoteId, unpassedForkIndex).toLeft("not in subset"))
      _   <- liftF(Logger[F].trace(s"checkSubset result: $sub"))
      _   <- liftF(Logger[F].trace(s"Before checkSuperset"))
      sup <- fromEither[F](checkSuperset(checksums, remoteId, unpassedForkIndex).toLeft("not in superset"))
      _   <- liftF(Logger[F].trace(s"checkSuperset result: $sup"))
      _   <- liftF(Logger[F].trace(s"No check succeeded"))
      _   <- fromEither[F](Either.left[ForkIdValidationResult, Unit](ErrLocalIncompatibleOrStale))
    } yield ()).value

    for {
      _   <- Logger[F].debug(s"Validating $remoteId")
      _   <- Logger[F].trace(s" list: $forks")
      _   <- Logger[F].trace(s"Unpassed fork $unpassedFork was found at index $unpassedForkIndex")
      res <- validate.map(_.swap)
      _   <- Logger[F].debug(s"Validation result is: $res")
    } yield res.getOrElse(Connect)
  }

  private def calculateChecksums(
      genesisHash: BlockHash,
      forks: List[BlockNumber]
  ): Vector[BigInt] = {
    val crc = new CRC32()
    crc.update(genesisHash.byteString.asByteBuffer)
    val genesisChecksum = BigInt(crc.getValue)

    genesisChecksum +: forks.map { fork =>
      crc.update(ByteUtils.bigIntToBytes(fork.value, 8))
      BigInt(crc.getValue)
    }.toVector
  }

  /** 1) If local and remote FORK_HASH matches, compare local head to FORK_NEXT.
    * The two nodes are in the same fork state currently.
    * They might know of differing future forks, but that’s not relevant until the fork triggers (might be postponed, nodes might be updated to match).
    * 1a) A remotely announced but remotely not passed block is already passed locally, disconnect, since the chains are incompatible.
    * 1b) No remotely announced fork; or not yet passed locally, connect.
    */
  private def checkMatchingHashes(
      checksum: BigInt,
      remoteId: ForkId,
      currentHeight: BlockNumber
  ): Option[ForkIdValidationResult] =
    remoteId match {
      case ForkId(hash, _) if checksum != hash            => None
      case ForkId(_, Some(next)) if currentHeight >= next => Some(ErrLocalIncompatibleOrStale)
      case _                                              => Some(Connect)
    }

  /** 2) If the remote FORK_HASH is a subset of the local past forks and the remote FORK_NEXT matches with the locally following fork block number, connect.
    * Remote node is currently syncing. It might eventually diverge from us, but at this current point in time we don’t have enough information.
    */
  private def checkSubset(
      checksums: Vector[BigInt],
      forks: List[BlockNumber],
      remoteId: ForkId,
      i: Int
  ): Option[ForkIdValidationResult] =
    checksums
      .zip(forks)
      .take(i)
      .collectFirst {
        case (sum, fork) if sum == remoteId.hash => if (fork == remoteId.next.getOrElse(0)) Connect else ErrRemoteStale
      }

  /** 3) If the remote FORK_HASH is a superset of the local past forks and can be completed with locally known future forks, connect.
    * Local node is currently syncing. It might eventually diverge from the remote, but at this current point in time we don’t have enough information.
    */
  private def checkSuperset(checksums: Vector[BigInt], remoteId: ForkId, i: Int): Option[ForkIdValidationResult] =
    checksums.drop(i).collectFirst { case sum if sum == remoteId.hash => Connect }

}
