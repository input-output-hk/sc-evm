package io.iohk.scevm.ledger

import cats.effect.IO
import io.iohk.scevm.db.storage.GetByNumberService
import io.iohk.scevm.domain
import io.iohk.scevm.domain.{BlockHash, ObftBlock}

class GetByNumberServiceStub(blocks: List[ObftBlock]) extends GetByNumberService[IO] {
  private val map = blocks.groupMapReduce(k => k.number)(identity)((a, _) => a)
  override def getHashByNumber(branchTip: BlockHash)(number: domain.BlockNumber): IO[Option[BlockHash]] =
    IO.pure(map.get(number).map(_.hash))
}
