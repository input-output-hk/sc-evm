package io.iohk.scevm.network

import cats.effect.{IO, Resource}
import fs2.Pipe
import fs2.concurrent.Topic
import io.iohk.scevm.domain.ObftBlock
import io.iohk.scevm.network.p2p.messages.OBFT1

object FullBlockResponseMock {
  def runWithResponseMock[A](
      fa: IO[A],
      peerEventsTopic: Topic[IO, PeerEvent],
      peerActionsTopic: Topic[IO, PeerAction],
      chain: Seq[ObftBlock],
      transformReplies: Pipe[IO, PeerAction, PeerAction] = identity
  ): IO[A] =
    subscribeToPeerActions(peerActionsTopic).use { mockResponseStream =>
      fs2.Stream
        .eval(fa)
        .concurrently(
          handleRequests(mockResponseStream.through(transformReplies), peerEventsTopic, chain)
        )
        .compile
        .lastOrError
    }

  private def subscribeToPeerActions(
      peerActionsTopic: Topic[IO, PeerAction]
  ): Resource[IO, fs2.Stream[IO, PeerAction]] =
    peerActionsTopic
      .subscribeAwait(1)

  private def handleRequests(
      stream: fs2.Stream[IO, PeerAction],
      peerEventsTopic: Topic[IO, PeerEvent],
      chain: Seq[ObftBlock]
  ): fs2.Stream[IO, PeerAction] =
    stream.evalTap {
      case PeerAction.MessageToPeer(
            pId,
            OBFT1.GetFullBlocks(requestId, blockHash, count)
          ) =>
        val blocks = chain
          .find(_.header.hash == blockHash)
          .map { block =>
            val targetBlockIndex = block.number.value
            val startIndex       = if (targetBlockIndex - count < 0) BigInt(0) else targetBlockIndex - count + 1
            (startIndex to targetBlockIndex).map { i =>
              chain(i.toInt)
            }.reverse
          }
          .getOrElse(List.empty)

        peerEventsTopic.publish1(
          PeerEvent.MessageFromPeer(pId, OBFT1.FullBlocks(requestId, blocks))
        )
      case _ =>
        IO.unit
    }

}
