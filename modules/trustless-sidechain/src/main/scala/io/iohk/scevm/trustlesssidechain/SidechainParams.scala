package io.iohk.scevm.trustlesssidechain

import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.BlockHash
import io.iohk.scevm.trustlesssidechain.cardano._

/** original type:
  * ```
  * data SidechainParams' = SidechainParams'
  *   { chainId :: Integer
  *   , genesisHash :: GenesisHash
  *   , -- @Just utxo@ denotes that we will use the oneshot minting policy, and @Nothing@
  *     -- will use the distributed set implementation.
  *
  *     -- | 'genesisUtxo' is a 'TxOutRef' used to initialize the internal
  *     -- policies in the side chain (e.g. for the 'UpdateCommitteeHash' endpoint)
  *     genesisUtxo :: TxOutRef
  *   , -- | 'thresholdNumerator' is the numerator for the ratio of the committee
  *     -- needed to sign off committee handovers / merkle roots
  *     thresholdNumerator :: Integer
  *   , -- | 'thresholdDenominator' is the denominator for the ratio of the
  *     -- committee needed to sign off committee handovers / merkle roots
  *     thresholdDenominator :: Integer
  *   }
  *  ```
  */
final case class SidechainParams(
    chainId: ChainId,
    genesisHash: BlockHash,
    genesisUtxo: UtxoId,
    thresholdNumerator: Int,
    thresholdDenominator: Int
)
