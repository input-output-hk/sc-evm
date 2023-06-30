package io.iohk.scevm.sidechain.committee

import cats.Applicative
import io.iohk.ethereum.crypto.AbstractSignatureScheme
import io.iohk.scevm.cardanofollower.datasource.{ValidEpochData, ValidLeaderCandidate}
import io.iohk.scevm.trustlesssidechain.cardano.{EpochNonce, MainchainEpoch}

class ValidEpochDataProviderStub[F[_]: Applicative, Scheme <: AbstractSignatureScheme](
    committee: Option[Set[ValidLeaderCandidate[Scheme]]],
    nonces: Map[MainchainEpoch, EpochNonce]
) {
  def getValidEpochData(mainchainEpoch: MainchainEpoch): F[Option[ValidEpochData[Scheme]]] =
    Applicative[F].pure(Applicative[Option].map2(nonces.get(mainchainEpoch), committee)((a, b) => ValidEpochData(a, b)))

}
