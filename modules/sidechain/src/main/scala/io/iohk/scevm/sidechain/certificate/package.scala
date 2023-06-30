package io.iohk.scevm.sidechain

import io.iohk.scevm.plutus.DatumCodec

package object certificate {

  implicit val sidechainEpochEncoder: DatumCodec[SidechainEpoch] = SidechainEpoch.deriving[DatumCodec]

}
