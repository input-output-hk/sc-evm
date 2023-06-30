package io.iohk.scevm.plutus

import com.softwaremill.diffx.Diff
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex

object PlutusDiffxInstances {
  implicit private val diffForByteString: Diff[ByteString] = Diff.useEquals[String].contramap(bs => Hex.toHexString(bs))

  implicit val diffForInteger: Diff[IntegerDatum]              = Diff.derived
  implicit val diffForByteStringDatum: Diff[ByteStringDatum]   = Diff.derived
  implicit val diffForListDatum: Diff[ListDatum]               = Diff.derived
  implicit val diffForConstructorDatum: Diff[ConstructorDatum] = Diff.derived
  implicit val diffForDatumMapItem: Diff[DatumMapItem]         = Diff.derived
  implicit val diffMapDatum: Diff[MapDatum]                    = Diff.derived
  implicit lazy val diffForDatum: Diff[Datum]                  = Diff.derived
}
