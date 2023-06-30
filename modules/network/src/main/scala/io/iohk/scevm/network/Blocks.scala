package io.iohk.scevm.network

import io.iohk.scevm.domain.{ObftBlock, ObftBody, ObftHeader}

object Blocks {

  def buildBlocks(headers: Seq[ObftHeader], bodies: Seq[ObftBody]): Seq[ObftBlock] = {
    val bodiesByTransactionsRoot = bodies.groupBy(_.transactionsRoot)
    for {
      header <- headers
      body   <- bodiesByTransactionsRoot.get(header.transactionsRoot).flatMap(_.headOption)
    } yield ObftBlock(header, body)
  }

}
