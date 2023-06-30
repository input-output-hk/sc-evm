package io.iohk.scevm.consensus.domain

import io.iohk.scevm.domain.ObftBlock

/** Represent an alternative branch at least composed of a executed ancestor and a not executed tip
  * @param ancestor the oldest block of the branch, must be a fully-executed block
  * @param belly a number of not yet executed blocks
  * @param tip last unvalidated block
  */
final case class BlocksBranch(ancestor: ObftBlock, belly: Vector[ObftBlock], tip: ObftBlock) {
  def unexecuted: Vector[ObftBlock] = belly :+ tip

  def toHeadersBranch: HeadersBranch = HeadersBranch(ancestor.header, belly.map(_.header), tip.header)
}

object BlocksBranch {
  def apply(ancestor: ObftBlock, tip: ObftBlock): BlocksBranch = BlocksBranch(ancestor, Vector.empty, tip)
}
