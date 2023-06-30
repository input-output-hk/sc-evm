package io.iohk.scevm.trustlesssidechain

import io.iohk.scevm.domain.{BlockHash, BlockNumber}
import io.iohk.scevm.plutus.DatumDecoder

final case class CheckpointDatum(sidechainBlockHash: BlockHash, sidechainBlockNumber: BlockNumber)

object CheckpointDatum {

  implicit val decoder: DatumDecoder[CheckpointDatum] = DatumDecoder.derive

}
