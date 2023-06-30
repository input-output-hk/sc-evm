package io.iohk.scevm.mpt

sealed trait MptProofError

object MptProofError {
  case object UnableRebuildMpt       extends MptProofError
  case object KeyNotFoundInRebuidMpt extends MptProofError
}
