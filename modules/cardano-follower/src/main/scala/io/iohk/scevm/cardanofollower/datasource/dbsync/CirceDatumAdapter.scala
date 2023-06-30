package io.iohk.scevm.cardanofollower.datasource.dbsync

import cats.syntax.all._
import io.circe
import io.circe.generic.semiauto.deriveDecoder
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.plutus._

object CirceDatumAdapter {

  // Those parser define the way the datum are serialized in db-sync in json

  private val cireIntegerDatumDecoder: circe.Decoder[IntegerDatum] = deriveDecoder[IntegerDatum]

  private val circeListDatumDecoder: circe.Decoder[ListDatum] = deriveDecoder[ListDatum]

  private val circeByteStringDatumDecoder: circe.Decoder[ByteStringDatum] = {
    implicit val byteStringDecoder: circe.Decoder[ByteString] = circe
      .Decoder[String]
      .flatMap(str =>
        Hex
          .decode(str)
          .fold(error => circe.Decoder.failedWithMessage(error), circe.Decoder.const)
      )
    deriveDecoder[ByteStringDatum]
  }

  private val circeMapDatumDecoder: circe.Decoder[MapDatum] = {
    implicit val mapItemDecoder: circe.Decoder[DatumMapItem] = deriveDecoder[DatumMapItem]
    deriveDecoder[MapDatum]
  }

  private val circeConstructorDatumDecoder: circe.Decoder[ConstructorDatum] = deriveDecoder[ConstructorDatum]

  implicit val circeDatumDecoder: circe.Decoder[Datum] =
    Seq[circe.Decoder[Datum]](
      circeConstructorDatumDecoder.widen,
      circeMapDatumDecoder.widen,
      circeByteStringDatumDecoder.widen,
      circeListDatumDecoder.widen,
      cireIntegerDatumDecoder.widen
    ).reduceLeft(_ or _)

}
