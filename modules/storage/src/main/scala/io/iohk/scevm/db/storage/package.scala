package io.iohk.scevm.db

import boopickle.Default.{Pickle, Unpickle}
import io.iohk.bytes.ByteString
import io.iohk.ethereum.utils.ByteUtils.{byteSequenceToBuffer, compactPickledBytes}
import io.iohk.scevm.domain.{BlockHash, ObftBody, ObftHeader}
import io.iohk.scevm.utils.Picklers._

package object storage {

  def hashedBlockHeaderSerializer: BlockHash => IndexedSeq[Byte] =
    _.byteString.toIndexedSeq

  def hashedBlockHeaderDeserializer: IndexedSeq[Byte] => BlockHash =
    bytes => BlockHash(ByteString.fromArrayUnsafe(bytes.toArray))

  def obftBlockBodySerializer: ObftBody => IndexedSeq[Byte] =
    body => compactPickledBytes(Pickle.intoBytes(body))

  def obftBlockBodyDeserializer: IndexedSeq[Byte] => ObftBody =
    (byteSequenceToBuffer _).andThen(Unpickle.apply[ObftBody].fromBytes)

  def obftBlockHeaderSerializer: ObftHeader => IndexedSeq[Byte] =
    header => compactPickledBytes(Pickle.intoBytes(header))

  def obftBlockHeaderDeserializer: IndexedSeq[Byte] => ObftHeader =
    (byteSequenceToBuffer _).andThen(Unpickle.apply[ObftHeader].fromBytes)

}
