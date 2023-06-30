package io.iohk.scevm.consensus.pos

import io.iohk.scevm.domain.ObftHeader

object SimplestHeaderComparator extends Ordering[ObftHeader] {

  /** Comparator of headers used to determine best candidates
    * @param header1 first header
    * @param header2 second header
    * @return positive, null, negative if header1 is greater, equals or less than header2
    */
  override def compare(header1: ObftHeader, header2: ObftHeader): Int =
    header1.number.compare(header2.number)
}
