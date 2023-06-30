package io.iohk.scevm.network.domain

import io.iohk.scevm.domain.ObftHeader

object ChainDensity {

  /** Computes the density of the chain from tip to ancestor
    * Formula: number_of_blocks / number_of_slots
    *
    * For example, if there are 20 slots between the tip (included) and the ancestor (excluded)
    * and there are 16 blocks, then the chain's density will be equal to 0.8.
    *
    * @return the density of the chain
    */
  def density(tip: ObftHeader, ancestor: ObftHeader): Double = {
    val numberBlocks = tip.number.distance(ancestor.number).value
    val numberSlots  = tip.slotNumber.number - ancestor.slotNumber.number

    if (numberBlocks <= 0 || numberSlots <= 0) 0.0
    else numberBlocks.toDouble / numberSlots.toDouble
  }

}
