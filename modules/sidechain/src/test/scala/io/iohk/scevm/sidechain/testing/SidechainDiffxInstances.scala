package io.iohk.scevm.sidechain.testing

import com.softwaremill.diffx.Diff
import io.iohk.ethereum.crypto.AnySignature
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.cardanofollower.testing.CardanoFollowerDiffxInstances._
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.sidechain.CrossChainSignaturesService.{
  CommitteeHandoverSignatures,
  CrossChainSignatures,
  MemberSignature,
  OutgoingTransactionSignatures
}
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.testing.CoreDiffxInstances._
import io.iohk.scevm.trustlesssidechain.SidechainParams

object SidechainDiffxInstances {
  implicit val abstractSignatureDiff: Diff[AnySignature] =
    Diff[String].contramap(s => Hex.toHexString(s.toBytes))
  implicit val diffForMemberSignature: Diff[MemberSignature]                             = Diff.derived
  implicit val diffForRootHash: Diff[RootHash]                                           = RootHash.deriving
  implicit val diffForOutgoingTransactionsSignature: Diff[OutgoingTransactionSignatures] = Diff.derived
  implicit val diffForHandoverSignatures: Diff[CommitteeHandoverSignatures]              = Diff.derived
  implicit val diffForChainId: Diff[ChainId]                                             = ChainId.deriving
  implicit val diffForSidechainParams: Diff[SidechainParams]                             = Diff.derived
  implicit val diffForCrossChainSignatures: Diff[CrossChainSignatures]                   = Diff.derived
}
