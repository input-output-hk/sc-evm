package io.iohk.scevm.testnode.eth

import io.iohk.scevm.domain.SignedTransaction

final case class EthBlockBody(transactionList: Seq[SignedTransaction], uncleNodesList: Seq[EthBlockHeader])
