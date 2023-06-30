package io.iohk.scevm.cardanofollower.testing

import com.softwaremill.diffx.Diff
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.scevm.cardanofollower.datasource.MainchainDataRepository.DbIncomingCrossChainTransaction
import io.iohk.scevm.plutus.DatumDecoder.DatumDecodeError
import io.iohk.scevm.plutus.PlutusDiffxInstances._
import io.iohk.scevm.testing.CoreDiffxInstances._
import io.iohk.scevm.trustlesssidechain._
import io.iohk.scevm.trustlesssidechain.cardano._

object CardanoFollowerDiffxInstances {
  implicit val diffForMainchainTxHash: Diff[MainchainTxHash]   = MainchainTxHash.deriving
  implicit val diffForUtxoId: Diff[UtxoId]                     = Diff.derived
  implicit val diffForDatumDecodeError: Diff[DatumDecodeError] = Diff.derived
  implicit def diffForRegisterValidatorDatum[SigningScheme <: AbstractSignatureScheme](implicit
      pubKeyDiff: Diff[SigningScheme#PublicKey],
      shortSigDiff: Diff[SigningScheme#SignatureWithoutRecovery]
  ): Diff[RegisterValidatorDatum[SigningScheme]]                                             = Diff.derived
  implicit val diffForMainchainBlockNumber: Diff[MainchainBlockNumber]                       = MainchainBlockNumber.deriving
  implicit val diffForDbIncomingCrossChainTransaction: Diff[DbIncomingCrossChainTransaction] = Diff.derived
  implicit val diffForMainchainEpoch: Diff[MainchainEpoch]                                   = MainchainEpoch.deriving
  implicit val diffForMainchainSlot: Diff[MainchainSlot]                                     = MainchainSlot.deriving
  implicit val diffForMainchainAddress: Diff[MainchainAddress]                               = MainchainAddress.deriving
  implicit val diffForSpentByInfo: Diff[SpentByInfo]                                         = Diff.derived
  implicit val diffForMainchainTxOutput: Diff[MainchainTxOutput]                             = Diff.derived
  implicit val diffForMainchainBlockInfo: Diff[MainchainBlockInfo]                           = Diff.derived
  implicit val diffForAssetName: Diff[AssetName]                                             = AssetName.deriving
  implicit val diffForMintAction: Diff[MintAction]                                           = Diff.derived

}
