package io.iohk.sidechains.relay

import io.iohk.ethereum.crypto.ECDSA

object RpcFormats {
  final case class EpochToUpload(epoch: Long, rootHashes: List[String])

  final case class GetSignaturesResponse(
      params: SidechainParams,
      committeeHandover: CommitteeHandoverSignatures,
      outgoingTransactions: List[OutgoingTransactionsSignatures]
  )
  final case class SidechainParams(
      chainId: String,
      genesisHash: String,
      genesisUtxo: String,
      thresholdNumerator: Int,
      thresholdDenominator: Int
  )

  final case class CommitteeHandoverSignatures(
      nextCommitteePubKeys: List[String],
      previousMerkleRootHash: Option[String],
      signatures: List[CrossChainSignaturesEntry]
  )

  final case class CrossChainSignaturesEntry(committeeMember: String, signature: String)

  final case class OutgoingTransactionsSignatures(
      merkleRootHash: String,
      previousMerkleRootHash: Option[String],
      signatures: List[CrossChainSignaturesEntry]
  )

  final case class CommitteeResponse(committee: List[MemberData], sidechainEpoch: Long) {
    def parseCommittee: List[ECDSA.PublicKey] =
      committee.map(c => ECDSA.PublicKey.fromHexUnsafe(c.sidechainPubKey))
  }
  final case class MemberData(sidechainPubKey: String)

  final case class StatusPartial(sidechain: SidechainStatusPartial)
  final case class SidechainStatusPartial(epoch: Long)
}
