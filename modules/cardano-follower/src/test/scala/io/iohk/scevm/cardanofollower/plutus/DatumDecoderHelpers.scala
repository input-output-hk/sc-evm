package io.iohk.scevm.cardanofollower.plutus

import io.circe.Json
import io.iohk.scevm.cardanofollower.datasource.dbsync.CirceDatumAdapter._
import io.iohk.scevm.plutus.DatumDecoder.DatumDecodeError
import io.iohk.scevm.plutus.{Datum, DatumDecoder}

object DatumDecoderHelpers {

  def decodeFromJson[T: DatumDecoder](json: Json): Either[DatumDecodeError, T] =
    json.as[Datum] match {
      case Left(value)  => Left(DatumDecodeError(value.getMessage))
      case Right(value) => DatumDecoder[T].decode(value)
    }
}
