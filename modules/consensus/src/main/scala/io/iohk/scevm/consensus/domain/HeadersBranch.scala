package io.iohk.scevm.consensus.domain

import io.iohk.scevm.domain.ObftHeader

/** Represent an alternative branch at least composed of a fully-executed ancestor and not executed tip
  *
  * @param ancestor the oldest block of the branch, must be a fully-executed block
  * @param belly a number of not executed blocks
  * @param tip that last not executed block
  */
final case class HeadersBranch(ancestor: ObftHeader, belly: Vector[ObftHeader], tip: ObftHeader) {
  def unexecuted: Vector[ObftHeader] = belly :+ tip
}

object HeadersBranch {
  def apply(ancestor: ObftHeader, tip: ObftHeader): HeadersBranch = HeadersBranch(ancestor, Vector.empty, tip)
}
