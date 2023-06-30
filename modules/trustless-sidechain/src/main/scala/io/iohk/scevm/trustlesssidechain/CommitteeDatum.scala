package io.iohk.scevm.trustlesssidechain

import io.iohk.scevm.plutus.DatumDecoder
import io.iohk.scevm.trustlesssidechain.cardano.Blake2bHash32

/*
 * data UpdateCommitteeHash = UpdateCommitteeHash
 *  { committeePubKeysHash :: ByteString
 *    -- ^ Hash of all lexicographically sorted public keys of the current committee members
 *  , sidechainEpoch :: Integer
 *    -- ^ sidechain epoch of the committee
 *  }
 */
final case class CommitteeDatum(committeePubKeysHash: Blake2bHash32, sidechainEpoch: Long)

object CommitteeDatum {

  implicit val decoder: DatumDecoder[CommitteeDatum] = DatumDecoder.derive

}
