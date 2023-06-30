package io.iohk.scevm.extvm.testevm

import io.iohk.scevm.domain.Address

class Storage(val address: Address, val storage: Map[BigInt, BigInt], cache: StorageCache)
    extends io.iohk.scevm.exec.vm.Storage[Storage] {

  def store(offset: BigInt, value: BigInt): Storage =
    new Storage(address, storage + (offset -> value), cache)

  def load(offset: BigInt): BigInt =
    storage.getOrElse(offset, cache.getStorageData(address, offset))
}
