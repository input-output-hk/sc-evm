package io.iohk.scevm.rpc.sidechain.controller

import io.circe.Json
import io.circe.literal.JsonStringContext
import io.iohk.bytes.ByteString
import io.iohk.ethereum.crypto.{ECDSA, ECDSASignature, ECDSASignatureNoRecovery, EdDSA}
import io.iohk.ethereum.utils.Hex
import io.iohk.scevm.config.BlockchainConfig.ChainId
import io.iohk.scevm.domain.{Address, BlockHash, BlockNumber, EpochPhase, Slot, Token}
import io.iohk.scevm.rpc.sidechain.CrossChainTransactionController.GetOutgoingTransactionsResponse
import io.iohk.scevm.rpc.sidechain.SidechainController._
import io.iohk.scevm.sidechain.transactions.MerkleProofService.SerializedMerkleProof
import io.iohk.scevm.sidechain.transactions._
import io.iohk.scevm.sidechain.{SidechainEpoch, ValidIncomingCrossChainTransaction}
import io.iohk.scevm.trustlesssidechain.SidechainParams
import io.iohk.scevm.trustlesssidechain.cardano._
import io.iohk.scevm.utils.SystemTime

object SidechainFixtures {

  final case class TestFixture[T](returnData: T, expectedOutcome: Json)

  val sidechainPubKeyHex =
    "0x333e47cab242fefe88d7da1caa713307290291897f100efb911672d317147f729211701e5c5e70690952bc2f5fa98478898128bc5c77f8837c392dae660c6bc4"
  val crossChainPubKeyHex =
    "0xc856df52f7ee7b9c341f34617bf9397736676754a868c8e7f6acab90f0ae6579aef4f7eb324ca3eb6351da1603d065035e5ec1690d7752b386b4228f23eace3a"

  val GetCurrentCandidatesFixture: TestFixture[List[CandidateRegistrationEntry]] = TestFixture(
    returnData = List(
      CandidateRegistrationEntry(
        sidechainPubKey = ECDSA.PublicKey.fromHexUnsafe(sidechainPubKeyHex),
        mainchainPubKey =
          EdDSA.PublicKey.fromHexUnsafe("3fd6618bfcb8d964f44beba4280bd91c6e87ac5bca4aa1c8f1cde9e85352660b"),
        crossChainPubKey = ECDSA.PublicKey.fromHexUnsafe(crossChainPubKeyHex),
        sidechainSignature = ECDSASignatureNoRecovery.fromHexUnsafe(
          "3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc6167425"
        ),
        mainchainSignature = EdDSA.Signature.fromHexUnsafe(
          "1fd2f1e5ad14c829c7359474764701cd74ab9c433c29b0bbafaa6bcf22376e9d651391d08ae6f40b418d2abf827c4c1fcb007e779a2beba7894d68012942c708"
        ),
        crossChainSignature = ECDSASignatureNoRecovery.fromHexUnsafe(
          "3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc6167425"
        ),
        utxo = UtxoInfo(
          UtxoId(
            MainchainTxHash(
              Hex.decodeUnsafe(
                "37666264613837343766366137666434313561373030383938303030313037663030653634663830383030333262303137663136303137326165383833343232"
              )
            ),
            2
          ),
          MainchainEpoch(2),
          MainchainBlockNumber(122),
          MainchainSlot(140),
          2
        ),
        stakeDelegation = Lovelace(1000),
        registrationStatus = CandidateRegistrationStatus.Pending,
        upcomingChange = Some(
          UpcomingRegistrationChange(
            CandidateRegistrationStatus.Active,
            EffectiveFrom(SidechainEpoch(30), MainchainEpoch(3)),
            MainchainTxHash.decodeUnsafe("abcde")
          )
        ),
        invalidReasons = Some(Seq("Some message"))
      )
    ),
    expectedOutcome = json"""[
               {
                  "sidechainPubKey": ${sidechainPubKeyHex},
                  "mainchainPubKey": "0x3fd6618bfcb8d964f44beba4280bd91c6e87ac5bca4aa1c8f1cde9e85352660b",
                  "crossChainPubKey": ${crossChainPubKeyHex},
                  "sidechainSignature": "0x3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc6167425",
                  "mainchainSignature": "0x1fd2f1e5ad14c829c7359474764701cd74ab9c433c29b0bbafaa6bcf22376e9d651391d08ae6f40b418d2abf827c4c1fcb007e779a2beba7894d68012942c708",
                  "crossChainSignature": "0x3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc6167425",
                  "utxo": {
                         "utxoId": "37666264613837343766366137666434313561373030383938303030313037663030653634663830383030333262303137663136303137326165383833343232#2",
                         "epoch": 2,
                         "mainchainBlockNumber": 122,
                         "mainchainSlotNumber": 140,
                         "txIndexWithinBlock": 2
                  },
                  "stakeDelegation": 1000,
                  "registrationStatus": "Pending",
                  "upcomingChange": {
                         "newState": "Active",
                         "effectiveFrom": {
                            "sidechainEpoch": 30,
                            "mainchainEpoch": 3
                         },
                         "mainchainTxHash": "0x0abcde"
                  },
                 "invalidReasons": ["Some message"]
               }
            ]"""
  )

  val GetCandidatesFixture: TestFixture[List[CandidateRegistrationEntry]] = TestFixture(
    List(
      CandidateRegistrationEntry(
        sidechainPubKey = ECDSA.PublicKey.fromHexUnsafe(sidechainPubKeyHex),
        mainchainPubKey =
          EdDSA.PublicKey.fromHexUnsafe("3fd6618bfcb8d964f44beba4280bd91c6e87ac5bca4aa1c8f1cde9e85352660b"),
        crossChainPubKey = ECDSA.PublicKey.fromHexUnsafe(crossChainPubKeyHex),
        sidechainSignature = ECDSASignatureNoRecovery.fromHexUnsafe(
          "3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc6167425"
        ),
        mainchainSignature = EdDSA.Signature.fromHexUnsafe(
          "1fd2f1e5ad14c829c7359474764701cd74ab9c433c29b0bbafaa6bcf22376e9d651391d08ae6f40b418d2abf827c4c1fcb007e779a2beba7894d68012942c708"
        ),
        crossChainSignature = ECDSASignatureNoRecovery.fromHexUnsafe(
          "3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc6167425"
        ),
        utxo = UtxoInfo(
          utxoId = UtxoId(
            MainchainTxHash(
              Hex.decodeUnsafe(
                "37666264613837343766366137666434313561373030383938303030313037663030653634663830383030333262303137663136303137326165383833343232"
              )
            ),
            0
          ),
          epoch = MainchainEpoch(0),
          blockNumber = MainchainBlockNumber(1),
          slotNumber = MainchainSlot(1),
          txIndexWithinBlock = 2
        ),
        stakeDelegation = Lovelace(42),
        registrationStatus = CandidateRegistrationStatus.Active,
        upcomingChange = Some(
          UpcomingRegistrationChange(
            newState = CandidateRegistrationStatus.Deregistered,
            effectiveFrom = EffectiveFrom(SidechainEpoch(123456), MainchainEpoch(123)),
            mainchainTxHash = MainchainTxHash(Hex.decodeUnsafe("ff" * 32))
          )
        ),
        invalidReasons = None
      )
    ),
    json"""
          [
            {
              "sidechainPubKey": ${sidechainPubKeyHex},
              "mainchainPubKey": "0x3fd6618bfcb8d964f44beba4280bd91c6e87ac5bca4aa1c8f1cde9e85352660b",
              "crossChainPubKey": ${crossChainPubKeyHex},
              "sidechainSignature": "0x3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc6167425",
              "mainchainSignature": "0x1fd2f1e5ad14c829c7359474764701cd74ab9c433c29b0bbafaa6bcf22376e9d651391d08ae6f40b418d2abf827c4c1fcb007e779a2beba7894d68012942c708",
              "crossChainSignature": "0x3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc6167425",
              "utxo": {
                "utxoId": "37666264613837343766366137666434313561373030383938303030313037663030653634663830383030333262303137663136303137326165383833343232#0",
                "epoch": 0,
                "mainchainBlockNumber": 1,
                "mainchainSlotNumber": 1,
                "txIndexWithinBlock": 2
              },
              "stakeDelegation": 42,
              "registrationStatus": "Active",
              "upcomingChange": {
                "newState": "Deregistered",
                "effectiveFrom": {
                  "sidechainEpoch": 123456,
                  "mainchainEpoch": 123
                },
                "mainchainTxHash" : "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
              }
            }
          ]
        """
  )

  val GetCommitteeFixture: TestFixture[GetCommitteeResponse] = TestFixture(
    GetCommitteeResponse(
      committee = List(
        GetCommitteeCandidate(
          ECDSA.PublicKey.fromHexUnsafe(sidechainPubKeyHex),
          Lovelace(42)
        )
      ),
      sidechainEpoch = SidechainEpoch(1)
    ),
    json"""
          {
            "committee": [
              {
                "sidechainPubKey": ${sidechainPubKeyHex},
                "stakeDelegation": 42
              }
            ],
            "sidechainEpoch": 1
          }
        """
  )

  val GetEpochSignaturesFixtures: TestFixture[GetSignaturesResponse] = TestFixture(
    returnData = GetSignaturesResponse(
      params = SidechainParams(
        chainId = ChainId(0x1),
        genesisHash = BlockHash(Hex.decodeUnsafe("abcdef2b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13")),
        UtxoId.parseUnsafe("7247647315d327d4e56fd3fe62d45be1dc3a76a647c910e0048cca8b97c8df3e#0"),
        5,
        7
      ),
      CommitteeHandoverSignatures(
        List(
          ECDSA.PublicKey.fromHexUnsafe(sidechainPubKeyHex)
        ),
        None,
        List(
          CrossChainSignaturesEntry(
            committeeMember = ECDSA.PublicKey.fromHexUnsafe(sidechainPubKeyHex),
            signature = ECDSASignature
              .fromHexUnsafe(
                "3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc616742582"
              )
              .toBytes
          )
        )
      ),
      outgoingTransactions = Nil
    ),
    expectedOutcome = json"""{
       "params":{
         "chainId":"0x01",
         "genesisHash":"0xabcdef2b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13",
         "genesisUtxo":"7247647315d327d4e56fd3fe62d45be1dc3a76a647c910e0048cca8b97c8df3e#0",
         "thresholdNumerator": 5,
         "thresholdDenominator": 7
       },
       "committeeHandover": {
        "nextCommitteePubKeys": [
          ${sidechainPubKeyHex}
        ],
        "signatures": [
          {
            "committeeMember":${sidechainPubKeyHex},
            "signature": "0x3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc616742582"
          }
        ]
      },
      "outgoingTransactions": []
    }"""
  )

  val GetFullEpochSignaturesFixtures: TestFixture[GetSignaturesResponse] = TestFixture(
    returnData = GetSignaturesResponse(
      params = SidechainParams(
        chainId = ChainId(0x1),
        genesisHash = BlockHash(Hex.decodeUnsafe("abcdef2b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13")),
        UtxoId.parseUnsafe("7247647315d327d4e56fd3fe62d45be1dc3a76a647c910e0048cca8b97c8df3e#0"),
        6,
        7
      ),
      CommitteeHandoverSignatures(
        List(
          ECDSA.PublicKey.fromHexUnsafe(sidechainPubKeyHex)
        ),
        Some(Hex.decodeUnsafe("0b2bc80b74bba96817248ed6a980a55e1cf7da1e073d747c875197683532741b")),
        List(
          CrossChainSignaturesEntry(
            committeeMember = ECDSA.PublicKey.fromHexUnsafe(sidechainPubKeyHex),
            signature = ECDSASignature
              .fromHexUnsafe(
                "3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc616742582"
              )
              .toBytes
          )
        )
      ),
      outgoingTransactions = List(
        OutgoingTransactionsSignatures(
          merkleRootHash =
            merkletree.RootHash(Hex.decodeUnsafe("0b2bc80b74bba96817248ed6a980a55e1cf7da1e073d747c875197683532741b")),
          Some(
            Hex.decodeUnsafe("0b2bc80b74bba96817248ed6a980a55e1cf7da1e073d747c875197683532741a")
          ),
          signatures = List(
            CrossChainSignaturesEntry(
              committeeMember = ECDSA.PublicKey.fromHexUnsafe(sidechainPubKeyHex),
              signature = ECDSASignature
                .fromHexUnsafe(
                  "3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc616742582"
                )
                .toBytes
            )
          )
        )
      )
    ),
    expectedOutcome = json"""{
       "params":{
         "chainId":"0x01",
         "genesisHash":"0xabcdef2b0a0016c2ccf8124d7dda71f6865283667850cc7b471f761d2bc1eb13",
         "genesisUtxo":"7247647315d327d4e56fd3fe62d45be1dc3a76a647c910e0048cca8b97c8df3e#0",
         "thresholdNumerator": 6,
         "thresholdDenominator": 7
       },
       "committeeHandover": {
        "nextCommitteePubKeys": [
          ${sidechainPubKeyHex}
        ],
        "previousMerkleRootHash": "0x0b2bc80b74bba96817248ed6a980a55e1cf7da1e073d747c875197683532741b",
        "signatures": [
          {
            "committeeMember":${sidechainPubKeyHex},
            "signature": "0x3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc616742582"
          }
        ]
      },
      "outgoingTransactions": [
        {
          "merkleRootHash": "0x0b2bc80b74bba96817248ed6a980a55e1cf7da1e073d747c875197683532741b",
          "previousMerkleRootHash": "0x0b2bc80b74bba96817248ed6a980a55e1cf7da1e073d747c875197683532741a",
          "signatures": [
            {
              "committeeMember": ${sidechainPubKeyHex},
              "signature": "0x3e8a8b29e513a08d0a66e22422a1a85d1bf409987f30a8c6fcab85ba38a85d0d27793df7e7fb63ace12203b062feb7edb5e6664ac1810b94c38182acc616742582"
            }
          ]
        }
      ]
    }
"""
  )

  val GetStatusFixture: TestFixture[GetStatusResponse] = TestFixture(
    returnData = GetStatusResponse(
      SidechainData(
        bestBlock = SidechainBlockData(
          BlockNumber(2),
          BlockHash(Hex.decodeUnsafe("222222")),
          SystemTime.TimestampSeconds(1500000000)
        ),
        stableBlock = SidechainBlockData(
          BlockNumber(1),
          BlockHash(Hex.decodeUnsafe("111111")),
          SystemTime.TimestampSeconds(1500000000)
        ),
        epoch = SidechainEpoch(0),
        epochPhase = EpochPhase.Regular,
        slot = Slot(2),
        nextEpochTimestamp = SystemTime.TimestampSeconds(1200000100)
      ),
      MainchainData(
        bestBlock = MainchainBlockData(
          MainchainBlockNumber(1),
          BlockHash(Hex.decodeUnsafe("abcd")),
          SystemTime.TimestampSeconds(0)
        ),
        stableBlock = MainchainBlockData(
          MainchainBlockNumber(0),
          BlockHash(Hex.decodeUnsafe("abcdef")),
          SystemTime.TimestampSeconds(0)
        ),
        epoch = MainchainEpoch(1),
        slot = MainchainSlot(1),
        nextEpochTimestamp = SystemTime.TimestampSeconds(1000020000)
      )
    ),
    expectedOutcome = json"""{
        "sidechain": {
          "bestBlock": {
            "number": "0x2",
            "hash": "0x222222",
            "timestamp": "0x59682f00"
          },
          "stableBlock": {
            "number": "0x1",
            "hash": "0x111111",
            "timestamp": "0x59682f00"
          },
          "epoch": 0,
          "epochPhase": "regular",
          "slot": "0x2",
          "nextEpochTimestamp": "0x47868c64"
        },
        "mainchain": {
          "bestBlock": {
            "number": 1,
            "hash": "0xabcd",
            "timestamp": "0x0"
          },
          "stableBlock": {
            "number": 0,
            "hash": "0xabcdef",
            "timestamp": "0x0"
          },
          "epoch": 1,
          "slot": 1,
          "nextEpochTimestamp": "0x3b9b1820"
        }
      }"""
  )

  val getOutgoingTxFixture: TestFixture[GetOutgoingTransactionsResponse] = TestFixture(
    returnData = GetOutgoingTransactionsResponse(
      Seq(OutgoingTransaction(Token(42), OutgoingTxRecipient(ByteString(1)), OutgoingTxId(1)))
    ),
    expectedOutcome = json"""{"transactions": [ { "value": "0x2a", "recipient": "0x01", "txIndex": 1 }] }"""
  )

  val getPendingTxFixture: TestFixture[GetPendingTransactionsResponse] = TestFixture(
    returnData = GetPendingTransactionsResponse(
      List(
        ValidIncomingCrossChainTransaction(
          Address(1),
          Token(1),
          MainchainTxHash.decodeUnsafe("01"),
          MainchainBlockNumber(200)
        )
      ),
      List(
        ValidIncomingCrossChainTransaction(
          Address(2),
          Token(2),
          MainchainTxHash.decodeUnsafe("02"),
          MainchainBlockNumber(300)
        )
      )
    ),
    expectedOutcome = json"""
            {
              "pending": [
                {
                  "recipient": "0x0000000000000000000000000000000000000001",
                  "value": "0x1",
                  "txId": "0x01",
                  "stableAtMainchainBlock": 200
                }
              ],
              "queued": [
                {
                  "recipient": "0x0000000000000000000000000000000000000002",
                  "value": "0x2",
                  "txId": "0x02",
                  "stableAtMainchainBlock": 300
                }
              ]
            }
            """
  )

  val getParamsFixture: TestFixture[GetSidechainParamsResponse] = TestFixture(
    returnData = GetSidechainParamsResponse(
      Some(UtxoId.parseUnsafe("e5b7bd5d52dc0b8d16b6bb15e82222bbacfb7661485a0ea3d9eba08c4fe41fdc#1")),
      UtxoId.parseUnsafe("e5b7bd5d52dc0b8d16b6bb15e82222bbacfb7661485a0ea3d9eba08c4fe41fdc#0"),
      genesisHash = BlockHash(Hex.decodeUnsafe("c493e33d3dbd43b553188849a5bee0307ff7c7280d407b3bbf46c8dc2b221fba")),
      ChainId.unsafeFrom(3),
      SidechainParamsThreshold(13, 17)
    ),
    expectedOutcome = json"""{"genesisMintUtxo": "e5b7bd5d52dc0b8d16b6bb15e82222bbacfb7661485a0ea3d9eba08c4fe41fdc#1",
            "genesisCommitteeUtxo": "e5b7bd5d52dc0b8d16b6bb15e82222bbacfb7661485a0ea3d9eba08c4fe41fdc#0",
            "genesisHash": "0xc493e33d3dbd43b553188849a5bee0307ff7c7280d407b3bbf46c8dc2b221fba",
            "chainId": "0x03",
            "threshold": {
              "numerator": 13,
              "denominator": 17
            }}"""
  )

  val getOutgoingTxMerkleProofFixture: TestFixture[GetMerkleProofResponse] =
    TestFixture(
      returnData = GetMerkleProofResponse(
        Some(
          GetMerkleProofData(
            SerializedMerkleProof(Hex.decodeUnsafe("abcd")),
            GetMerkleProofInfoResponse(
              OutgoingTransaction(Token(42), OutgoingTxRecipient(ByteString(1)), OutgoingTxId(1)),
              merkletree.RootHash(Hex.decodeUnsafe("e5b7bd"))
            )
          )
        ),
        SidechainEpoch(11)
      ),
      expectedOutcome = json"""{ "proof": {
                                    "bytes": "0xabcd",
                                    "info": {
                                      "transaction": {
                                         "value": "0x2a",
                                         "recipient": "0x01",
                                         "txIndex": 1
                                      },
                                      "merkleRootHash": "0xe5b7bd"
                                    }
                                  },
                                  "sidechainEpoch": 11
                               }"""
    )

  val getOutgoingTxMerkleProofNoProofFixture: TestFixture[GetMerkleProofResponse] =
    TestFixture(
      returnData = GetMerkleProofResponse(
        None,
        SidechainEpoch(11)
      ),
      expectedOutcome = json"""{ "sidechainEpoch": 11 }"""
    )
}
