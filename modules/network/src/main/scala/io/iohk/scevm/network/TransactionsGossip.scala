package io.iohk.scevm.network

import fs2.Pipe
import io.iohk.scevm.network.PeerAction.MessageToPeer
import io.iohk.scevm.network.TransactionsImport.NewTransactions
import io.iohk.scevm.network.p2p.messages.OBFT1.{NewPooledTransactionHashes, SignedTransactions}

import scala.util.Random

object TransactionsGossip {

  def gossipToAll[F[_]]: Pipe[F, (NewTransactions, Map[PeerId, PeerWithInfo]), MessageToPeer] =
    _.flatMap { case (newTransactions, peersState) =>
      val recipients = {
        val peersMinusSender = newTransactions.fromOpt match {
          case Some(from) => peersState.values.filterNot(_.id == from)
          case None       => peersState.values
        }
        Random.shuffle(peersMinusSender)
      }

      val gossipMessages = {
        val sqrt = scala.math.sqrt(recipients.size).ceil.toInt
        val highBandwidthMessages = recipients
          .take(sqrt)
          .map(peerWithInfo =>
            MessageToPeer(peerWithInfo.peer.id, SignedTransactions(newTransactions.signedTransactions.toList))
          )
        val lowBandwidthMessages = recipients
          .drop(sqrt)
          .map(peerWithInfo =>
            MessageToPeer(
              peerWithInfo.peer.id,
              NewPooledTransactionHashes(newTransactions.signedTransactions.map(_.hash).toList)
            )
          )

        highBandwidthMessages ++ lowBandwidthMessages
      }

      fs2.Stream.emits(gossipMessages.toSeq)
    }

}
