package io.iohk.scevm.domain

import io.iohk.bytes.ByteString
import io.iohk.scevm.utils.SystemTime.UnixTimestamp

final case class ObftGenesisAccount(
    balance: UInt256,
    code: Option[ByteString],
    nonce: Option[Nonce],
    storage: Map[UInt256, UInt256]
)

final case class ObftGenesisData(
    coinbase: Address,
    gasLimit: BigInt,
    timestamp: UnixTimestamp,
    alloc: Map[Address, ObftGenesisAccount],
    accountStartNonce: Nonce
)
