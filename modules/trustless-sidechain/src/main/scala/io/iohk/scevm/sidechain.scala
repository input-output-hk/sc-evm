package io.iohk.scevm

import cats.Show
import io.estatico.newtype.macros.newtype

import scala.language.implicitConversions

package object sidechain {
  @newtype final case class SidechainEpoch(number: Long) {
    def prev: SidechainEpoch = SidechainEpoch(number - 1)
    def next: SidechainEpoch = SidechainEpoch(number + 1)
  }
  object SidechainEpoch {
    implicit val show: Show[SidechainEpoch] = deriving
  }
}
