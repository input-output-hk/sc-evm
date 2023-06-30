package io.iohk.scevm.rpc.sidechain

import io.iohk.ethereum.crypto.{ECDSA, ECDSASignature, ECDSASignatureNoRecovery, EdDSA}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{Address, BlockHash, BlockNumber, EpochPhase, Slot, Token}
import io.iohk.scevm.rpc.armadillo.{Documentation, DocumentationImplicits}
import io.iohk.scevm.rpc.sidechain.CrossChainTransactionController.GetOutgoingTransactionsResponse
import io.iohk.scevm.rpc.sidechain.SidechainController._
import io.iohk.scevm.sidechain.transactions.MerkleProofService.SerializedMerkleProof
import io.iohk.scevm.sidechain.transactions.merkletree.RootHash
import io.iohk.scevm.sidechain.transactions.{OutgoingTransaction, OutgoingTxId, OutgoingTxRecipient}
import io.iohk.scevm.sidechain.{SidechainEpoch, ValidIncomingCrossChainTransaction}
import io.iohk.scevm.trustlesssidechain.SidechainParams
import io.iohk.scevm.trustlesssidechain.cardano._
import io.iohk.scevm.utils.SystemTime.TimestampSeconds

// scalastyle:off magic.number
// scalastyle:off line.size.limit
trait SidechainDocumentationImplicits extends DocumentationImplicits {

  implicit val getCommitteeResponseDocumentation: Documentation[GetCommitteeResponse] = Documentation(
    """Object that contains the following fields:
      |- `committee`: List of objects representing a current member of the committee, with the following fields
      |  - `sidechainPubKey`: Hexadecimal string of 64 bytes representing the sidechain public key of an SPO
      |  - `stakeDelegation`: Integer representing the amount of lovelace staked
      |- sidechainEpoch: Integer representing the sidechain epoch""".stripMargin,
    GetCommitteeResponse(
      List(
        GetCommitteeCandidate(
          ECDSA.PublicKey.fromHexUnsafe(
            "5f12a125921a7a899efc8d4e0ec2e5c0287e50087bed59622b4e70c9fc7e75c17569e81b60395756327a8ecfd394af7dadcd00875803e351fe5d7899ce45ce21"
          ),
          Lovelace(3249652454L)
        ),
        GetCommitteeCandidate(
          ECDSA.PublicKey.fromHexUnsafe(
            "c856df52f7ee7b9c341f34617bf9397736676754a868c8e7f6acab90f0ae6579aef4f7eb324ca3eb6351da1603d065035e5ec1690d7752b386b4228f23eace3a"
          ),
          Lovelace(3249235250L)
        )
      ),
      SidechainEpoch(42)
    )
  )

  implicit val getMerkleProofResponseDocumentation: Documentation[GetMerkleProofResponse] = Documentation(
    """Object that contains the following fields:
      |- `result`: Object representing the Merkle proof with the following fields:
      |  - `proof` (optional): The Merkle proof
      |  - `info`: Object that contains additional information like the Merkle root hash or the transaction matching the proof
      |- `sidechainEpoch`: Integer representing the sidechain epoch""".stripMargin,
    GetMerkleProofResponse(
      Some(
        GetMerkleProofData(
          SerializedMerkleProof(Hex.decodeUnsafe("123")),
          GetMerkleProofInfoResponse(
            OutgoingTransaction(
              Token(1000),
              OutgoingTxRecipient(Hex.decodeUnsafe("456")),
              OutgoingTxId(0L)
            ),
            RootHash(Hex.decodeUnsafe("1a9f9b8967b06ecf3b073f028ee2c2ffabe20cfe27c6eb154ccffbcbd8c45df4"))
          )
        )
      ),
      SidechainEpoch(10000L)
    )
  )

  implicit val getOutgoingTransactionsResponseDocumentation: Documentation[GetOutgoingTransactionsResponse] =
    Documentation(
      """List of objects representing transactions from sidechain to mainchain. These objects have the following fields:
        |- `value`: Number of tokens transferred to the mainchain
        |- `recipient`: Address on the mainchain that will received the tokens
        |- `txIndex`: Index of the outgoing transaction""".stripMargin,
      GetOutgoingTransactionsResponse(
        Seq(
          OutgoingTransaction(
            Token(1000),
            OutgoingTxRecipient(Hex.decodeUnsafe("123")),
            OutgoingTxId(0L)
          )
        )
      )
    )

  implicit val getPendingTransactionsResponseDocumentation: Documentation[GetPendingTransactionsResponse] =
    Documentation(
      """Object that contains two set of fields (`pending` and `queued`) with the same structure: a list of object with the following fields:
      |- recipient: Address in the sidechain that will receive the token
      |- value: Number of tokens transferred to the sidechain
      |- txId: Object representing a UTXO with the following fields:
      |  - txHash: Hash of the output transaction associated to the UTXO
      |  - index: Index of the output transaction associated to the UTXO
      |Queue transactions correspond to transactions that are already stable on the mainchain, and pending transactions correspond to transactions that are not stable yet.""".stripMargin,
      GetPendingTransactionsResponse(
        List(
          ValidIncomingCrossChainTransaction(
            Address(Hex.decodeUnsafe("12de31509cdc1c3e9b8987c15b1124dee71455a2")),
            Token(1000),
            MainchainTxHash(Hex.decodeUnsafe("7247647315d327d4e56fd3fe62d45be1dc3a76a647c910e0048cca8b97c8df3e")),
            MainchainBlockNumber(1)
          )
        ),
        List.empty
      )
    )

  implicit val getSidechainParamsResponseDocumentation: Documentation[GetSidechainParamsResponse] = Documentation(
    """Object that contains the following fields:
      |- `genesisMintUtxo`: UTXO consumed when creating the initial committee NFT. Having this field ensure that only one NFT can be minted. Can be the same as `genesisCommitteeUtxo`
      |- `genesisCommitteeUtxo`: UTXO consumed when creating the initial committee NFT. Having this field ensure that only one NFT can be minted
      |- `genesisHash`: Hexadecimal string of 32 bytes representing the hash of the genesis block
      |- `chainId`: Hexadecimal string representing the chain ID
      |- `threshold`: Object with two fields, `numerator` and `denominator`, which represents the relative number of committee members signatures required for validation of on-chain messages""".stripMargin,
    GetSidechainParamsResponse(
      Some(UtxoId.parseUnsafe("e5b7bd5d52dc0b8d16b6bb15e82222bbacfb7661485a0ea3d9eba08c4fe41fdc#1")),
      UtxoId.parseUnsafe("e5b7bd5d52dc0b8d16b6bb15e82222bbacfb7661485a0ea3d9eba08c4fe41fdc#0"),
      genesisHash = BlockHash(Hex.decodeUnsafe("c493e33d3dbd43b553188849a5bee0307ff7c7280d407b3bbf46c8dc2b221fba")),
      chainId = ChainId.unsafeFrom(78),
      threshold = SidechainParamsThreshold(
        numerator = 2,
        denominator = 3
      )
    )
  )

  implicit val getSignaturesResponseDocumentation: Documentation[GetSignaturesResponse] = Documentation(
    "Object that contains information related to the sidechain parameters (chain ID, hash of the genesis block, UTXO), as well as information related to the next committee handover (sidechain public key of the next committee and current committee signatures) and the outgoing transactions signatures",
    GetSignaturesResponse(
      SidechainParams(
        ChainId(0x4e),
        BlockHash(Hex.decodeUnsafe("5fa4f534a29fe9a85e87b8ea462843c847937d2a9c0190cea5bfbbc5c5b419c5")),
        UtxoId.parseUnsafe("a6e5f30a230ad5ecebd4d88e79f222f2637431ce807f6da8bb266860de32c097#0"),
        2,
        3
      ),
      CommitteeHandoverSignatures(
        List(
          ECDSA.PublicKey.fromHexUnsafe(
            "82067848815ffa513b52ff458a6b0cab853b0238ea4fe87ea32493762e1e151038adad0c1e621a07e285e4b013170346160556513c266597d2b54ecabad5cb77"
          ),
          ECDSA.PublicKey.fromHexUnsafe(
            "d3dbfcb8e7797dc44ac0981516c007093c8195850e062ae56b01a9baf62ee62c8624cf86fff310e9c93f7edd19b166c4d4b3df52d611c52abdc30b4733f08ad9"
          ),
          ECDSA.PublicKey.fromHexUnsafe(
            "32745f6d29337d26546ebfeffb6ce503378f580be024dfbab62052d32b515ea1c18ae15d8640079132ca29f96413562c0a6e0d3c4123100ceec6d9d84e71b676"
          )
        ),
        Some(Hex.decodeUnsafe("1a9f9b8967b06ecf3b073f028ee2c2ffabe20cfe27c6eb154ccffbcbd8c45df4")),
        List(
          CrossChainSignaturesEntry(
            ECDSA.PublicKey.fromHexUnsafe(
              "d3dbfcb8e7797dc44ac0981516c007093c8195850e062ae56b01a9baf62ee62c8624cf86fff310e9c93f7edd19b166c4d4b3df52d611c52abdc30b4733f08ad9"
            ),
            ECDSASignature
              .fromHexUnsafe(
                "9bf40cc7d2504603e80bf32c2aaa55d2f28db6f454cab4b2f86bebce332da77f54419c5ec1d81e4e2ca30f380517c7c22c0b2935130451baf3655a397c8260571b"
              )
              .toBytes
          ),
          CrossChainSignaturesEntry(
            ECDSA.PublicKey.fromHexUnsafe(
              "32745f6d29337d26546ebfeffb6ce503378f580be024dfbab62052d32b515ea1c18ae15d8640079132ca29f96413562c0a6e0d3c4123100ceec6d9d84e71b676"
            ),
            ECDSASignature
              .fromHexUnsafe(
                "4a71993d6211d7c46ef1f16638b3b92da163c1e84a9c57fd17ee0353cc4f136d72eed0fdf28139fa982137280db80cec87e5b374eb360c83eea3444c3162eb2e1b"
              )
              .toBytes
          ),
          CrossChainSignaturesEntry(
            ECDSA.PublicKey.fromHexUnsafe(
              "77dfa6de7ab4789d4472b8d5bfced9995df87739356705a89f91fa7383418aca180c2835c0a79d555e7f4871f8ae8caad9187e5205c23056b08a57aebe41fd23"
            ),
            ECDSASignature
              .fromHexUnsafe(
                "125bc87921955dd073b1ad3fb1e08595c253265b27ac19bbd2426ed7af4fecf654f92fe8d8e66005fc158eb141583df0dac50d33cae54346270351748bf389401b"
              )
              .toBytes
          )
        )
      ),
      List(
        OutgoingTransactionsSignatures(
          RootHash(Hex.decodeUnsafe("1a9f9b8967b06ecf3b073f028ee2c2ffabe20cfe27c6eb154ccffbcbd8c45df4")),
          None,
          List(
            CrossChainSignaturesEntry(
              ECDSA.PublicKey.fromHexUnsafe(
                "5f12a125921a7a899efc8d4e0ec2e5c0287e50087bed59622b4e70c9fc7e75c17569e81b60395756327a8ecfd394af7dadcd00875803e351fe5d7899ce45ce21"
              ),
              ECDSASignature
                .fromHexUnsafe(
                  "3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc616742582"
                )
                .toBytes
            )
          )
        )
      )
    )
  )

  implicit val getSignaturesToUploadResponseDocumentation: Documentation[GetSignaturesToUploadResponse] = Documentation(
    """List of objects with the following fields:
      |- epoch: Integer representing the sidechain epoch
      |- rootHashes: List of Merkle root hashes""".stripMargin,
    List.empty
  )

  implicit val getStatusResponseDocumentation: Documentation[GetStatusResponse] = Documentation(
    "Object that contains data related to the status of both the mainchain and the sidechain, like their best and stables blocks, as well as the current epoch or the timestamp associated to the next epoch.",
    GetStatusResponse(
      SidechainData(
        SidechainBlockData(
          BlockNumber(0x2994),
          BlockHash(Hex.decodeUnsafe("a0f55b02b92589ebcdfce0c5f9063f224229716c4e25110a1c8f5667712cf5da")),
          TimestampSeconds(0x63734bd3)
        ),
        SidechainBlockData(
          BlockNumber(0x2958),
          BlockHash(Hex.decodeUnsafe("72eb3359c424f7dc06638c95a80476ca4740e78c00117bb8696c5257f1393eb1")),
          TimestampSeconds(0x63734aa7)
        ),
        SidechainEpoch(2345),
        EpochPhase.Regular,
        Slot(0x19c447),
        TimestampSeconds(0x63735510)
      ),
      MainchainData(
        MainchainBlockData(
          MainchainBlockNumber(630141),
          BlockHash(Hex.decodeUnsafe("337919de559ca8481251d591d02896660da719bbfe3e16e11714787ac36466ae")),
          TimestampSeconds(0x63734bc2)
        ),
        MainchainBlockData(
          MainchainBlockNumber(630105),
          BlockHash(Hex.decodeUnsafe("d6c36777491e5fd58657376dee80d0aef5e3d4cfd913fa59046590760e80f78b")),
          TimestampSeconds(0x637348a8)
        ),
        MainchainEpoch(1936),
        MainchainSlot(13937178),
        TimestampSeconds(0x63735510)
      )
    )
  )

  implicit val listOfCandidateRegistrationEntryDocumentation: Documentation[List[CandidateRegistrationEntry]] =
    Documentation(
      """List of objects that contains the following fields:
      |- `sidechainPubKey`: Hexadecimal string of 64 bytes representing the sidechain public key of an SPO
      |- `mainchainPubKey`: Hexadecimal string of 32 bytes representing the mainchain public key of an SPO
      |- `sidechainSignature`: Hexadecimal string of 64 bytes representing the registration message signed with the SPO's sidechain private key
      |- `mainchainSignature`: Hexadecimal string of 64 bytes representing the registration message signed with the SPO's mainchain private key
      |- `utxo`: Object that contains the following fields:
      |  - `utxoId`: The Id of the UTXO associated to the registration, composed of a transaction hash and a transaction index
      |  - `mainchainBlockNumber`: Integer representing the block number on the mainchain that contains the transaction associated to the registration
      |  - `txIndexWithinBlock`: Integer representing the index of the transaction inside the mainchain block
      |- `stakeDelegation`: Integer representing the number of lovelace
      |- `registrationStatus`: One of the following string: `Invalid`, `Pending`, `Active`, `Superseded`, or `Deregistered`
      |- `upcomingChange` (optional): Object that contains the following fields:
      |  - `newState`: The next registration status
      |  - `effectiveFrom`: Object that contains the following fields:
      |    - sidechainEpoch: Integer representing the sidechain epoch where the new registration will be active
      |    - mainchainEpoch: Integer representing the mainchain epoch where the new registration will be active
      |  - `mainchainTxHash`: The hash of the mainchain transaction that contains the new registration""".stripMargin,
      List(
        CandidateRegistrationEntry(
          ECDSA.PublicKey.fromHexUnsafe(
            "5f12a125921a7a899efc8d4e0ec2e5c0287e50087bed59622b4e70c9fc7e75c17569e81b60395756327a8ecfd394af7dadcd00875803e351fe5d7899ce45ce21"
          ),
          EdDSA.PublicKey.fromHexUnsafe("739018c928d0e171f1c4b8714d3def1094bde53158650ee6683834f297059fbe"),
          ECDSA.PublicKey.fromHexUnsafe(
            "5f12a125921a7a899efc8d4e0ec2e5c0287e50087bed59622b4e70c9fc7e75c17569e81b60395756327a8ecfd394af7dadcd00875803e351fe5d7899ce45ce21"
          ),
          ECDSASignatureNoRecovery.fromHexUnsafe(
            "341a2c0ce6dfb804ae47c73dac162564ab293f58becb0a29f3ff1f25f97b489a70457a01b5afd1393d91e4f837194ca6122244a1a0f1b876a84f9edfe40a71b4"
          ),
          EdDSA.Signature.fromHexUnsafe(
            "b69db2cd922ad74c986ebe33e2eed911ea27e6856ebe5b07f4648ee74ff02cce069474719e841dc9fd469c9545f2b4b109efa0ce85c9ce68720e3457ea2a2708"
          ),
          ECDSASignatureNoRecovery.fromHexUnsafe(
            "341a2c0ce6dfb804ae47c73dac162564ab293f58becb0a29f3ff1f25f97b489a70457a01b5afd1393d91e4f837194ca6122244a1a0f1b876a84f9edfe40a71b4"
          ),
          UtxoInfo(
            UtxoId.parseUnsafe("f62cba7c9d27bace09b1ca1c8685400e8b0d12dec92726860d6b8620091b18f7#1"),
            MainchainEpoch(230),
            MainchainBlockNumber(597968),
            MainchainSlot(139371),
            0
          ),
          Lovelace(3249652454L),
          CandidateRegistrationStatus.Active,
          upcomingChange = None,
          invalidReasons = None
        ),
        CandidateRegistrationEntry(
          ECDSA.PublicKey.fromHexUnsafe(
            "eb3a752840b36e0583b5dcc7e03d1d963f76013e3b2b8399025067d532ec88aa5a1953ceb82b69262918a2986eea8e90b2fd90f872fc35917d1837c4f2c2b568"
          ),
          EdDSA.PublicKey.fromHexUnsafe("7d8f0c5b8bc120e5dd778256f84baf80ed192e974bc01b6bf745f3803f00ceb2"),
          ECDSA.PublicKey.fromHexUnsafe(
            "eb3a752840b36e0583b5dcc7e03d1d963f76013e3b2b8399025067d532ec88aa5a1953ceb82b69262918a2986eea8e90b2fd90f872fc35917d1837c4f2c2b568"
          ),
          ECDSASignatureNoRecovery.fromHexUnsafe(
            "60d6aec87f74a23340311b565d418ac0ca68a7ec4798bfa004e53e0869147dca4aa54e757e09993f2b9cb71c08be9efdc577a3c5515a1bead98cfdf25f197f80"
          ),
          EdDSA.Signature.fromHexUnsafe(
            "f02bfeceb6cdba2f5e3016dac58f6da57ff85fb5ca67b12be88fca3a8ac30675bd93f13ed2e92e0036b9cffa17b1782863ffb0ce59b22abbb767a9892b135a0a"
          ),
          ECDSASignatureNoRecovery.fromHexUnsafe(
            "60d6aec87f74a23340311b565d418ac0ca68a7ec4798bfa004e53e0869147dca4aa54e757e09993f2b9cb71c08be9efdc577a3c5515a1bead98cfdf25f197f80"
          ),
          UtxoInfo(
            UtxoId.parseUnsafe("a091c6a221067726df20e4fa256f1cbf2116841581312b1bb8f756b8d9782aca#1"),
            MainchainEpoch(229),
            MainchainBlockNumber(564020),
            MainchainSlot(137932),
            0
          ),
          Lovelace(3249235250L),
          CandidateRegistrationStatus.Pending,
          upcomingChange = Some(
            UpcomingRegistrationChange(
              CandidateRegistrationStatus.Active,
              EffectiveFrom(SidechainEpoch(1000), MainchainEpoch(230)),
              MainchainTxHash(Hex.decodeUnsafe("7247647315d327d4e56fd3fe62d45be1dc3a76a647c910e0048cca8b97c8df3e"))
            )
          ),
          invalidReasons = None
        )
      )
    )

  implicit val outgoingTxIdDocumentation: Documentation[OutgoingTxId] = Documentation(
    "Integer representing the id of an outgoing transaction",
    OutgoingTxId(0L)
  )

  implicit val sidechainEpochParamDocumentation: Documentation[SidechainEpochParam] = Documentation(
    "Integer representing an epoch number, or the string `latest`",
    SidechainEpochParam.ByEpoch(SidechainEpoch(42))
  )

}
// scalastyle:on line.size.limit
// scalastyle:on magic.number
