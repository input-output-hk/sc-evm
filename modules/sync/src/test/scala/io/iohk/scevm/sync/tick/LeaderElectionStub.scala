package io.iohk.scevm.sync.tick

import cats.Applicative
import io.iohk.ethereum.crypto.ECDSA
import io.iohk.scevm.domain.Slot
import io.iohk.scevm.ledger.LeaderElection
import io.iohk.scevm.ledger.LeaderElection.ElectionFailure

class LeaderElectionStub[F[_]: Applicative](result: Either[ElectionFailure, ECDSA.PublicKey])
    extends LeaderElection[F] {
  override def getSlotLeader(slot: Slot): F[Either[ElectionFailure, ECDSA.PublicKey]] = Applicative[F].pure(result)
}
