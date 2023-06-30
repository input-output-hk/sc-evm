package io.iohk.scevm.cardanofollower.plutus

import io.iohk.scevm.domain.Address
import io.iohk.scevm.plutus.DatumDecoder.{DatumConstructorOps, DatumDecodeError, DatumOpsFromDatum}
import io.iohk.scevm.plutus.{Datum, DatumDecoder}

/** @param sidechainAddress - sidechain address of recipient
  */
final case class TransactionRecipientDatum(sidechainAddress: Address)

object TransactionRecipientDatum {
  implicit val decoder: DatumDecoder[TransactionRecipientDatum] = (datum: Datum) =>
    for {
      constr  <- datum.asConstructor(0)
      address <- constr.getField(0).asBytes.flatMap(Address.fromBytes(_).left.map(DatumDecodeError.apply))
    } yield TransactionRecipientDatum(address)
}
