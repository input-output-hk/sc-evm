package io.iohk.scevm.db.storage

object Namespaces {
  val ReceiptsNamespace: IndexedSeq[Byte]                  = IndexedSeq[Byte]('r'.toByte)
  val HeaderNamespace: IndexedSeq[Byte]                    = IndexedSeq[Byte]('h'.toByte)
  val BodyNamespace: IndexedSeq[Byte]                      = IndexedSeq[Byte]('b'.toByte)
  val NodeNamespace: IndexedSeq[Byte]                      = IndexedSeq[Byte]('n'.toByte)
  val CodeNamespace: IndexedSeq[Byte]                      = IndexedSeq[Byte]('c'.toByte)
  val ChainWeightNamespace: IndexedSeq[Byte]               = IndexedSeq[Byte]('w'.toByte)
  val AppStateNamespace: IndexedSeq[Byte]                  = IndexedSeq[Byte]('s'.toByte)
  val KnownNodesNamespace: IndexedSeq[Byte]                = IndexedSeq[Byte]('k'.toByte)
  val HeightsNamespace: IndexedSeq[Byte]                   = IndexedSeq[Byte]('i'.toByte)
  val TransactionMappingNamespace: IndexedSeq[Byte]        = IndexedSeq[Byte]('l'.toByte)
  val AlternativeBlockHeadersNamespace: IndexedSeq[Byte]   = IndexedSeq[Byte]('1'.toByte)
  val AlternativeChainsNamespace: IndexedSeq[Byte]         = IndexedSeq[Byte]('2'.toByte)
  val StableBodyNamespace: IndexedSeq[Byte]                = IndexedSeq[Byte]('3'.toByte)
  val StableHeaderNamespace: IndexedSeq[Byte]              = IndexedSeq[Byte]('4'.toByte)
  val ObftAppStateNamespace: IndexedSeq[Byte]              = IndexedSeq[Byte]('5'.toByte)
  val StableBlockNumberMapping: IndexedSeq[Byte]           = IndexedSeq[Byte]('6'.toByte)
  val AlternativeReverseMappingNamespace: IndexedSeq[Byte] = IndexedSeq[Byte]('7'.toByte)

  val nsSeq: Seq[IndexedSeq[Byte]] = Seq(
    ReceiptsNamespace,
    HeaderNamespace,
    BodyNamespace,
    NodeNamespace,
    CodeNamespace,
    ChainWeightNamespace,
    AppStateNamespace,
    KnownNodesNamespace,
    HeightsNamespace,
    TransactionMappingNamespace,
    AlternativeBlockHeadersNamespace,
    AlternativeChainsNamespace,
    StableBodyNamespace,
    StableHeaderNamespace,
    ObftAppStateNamespace,
    StableBlockNumberMapping,
    AlternativeReverseMappingNamespace
  )
}
