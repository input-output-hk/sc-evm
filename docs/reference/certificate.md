# Sidechain certificate

This document outlines how the sidechain certificate is created on the sidechain side. Whoever wants to process it should use this document as a reference. The sidechain certificate consists of two parts. The first part contains committee members' signatures for the handover of the control to the next committee. The second part includes signed proof of cross-chain transactions that happened on the sidechain during the given sidechain epoch. Due to the lack of BLS primitives on Cardano it is not possible to implement proper ATMS, because of that the current implementation uses append-only scheme and ECDSA signatures. In the append-only scheme each validator signs the same message producing a signature. Then, all signatures are concatenated and submitted on the main chain.

## Handover certificate

Message to be signed:

```scala
final private case class UpdateCommitteeMessage(
  sidechainParams: SidechainParams//1,
  newCommitteePubKeys: NonEmptyList[EcdsaCompressedPubKey]//2,
  previousMerkleRoot: Option[RootHash]//3,
  sidechainEpoch: SidechainEpoch//4
)
```

where:

1. sidechainParams - a structure that uniquely identifies a given sidechain (see [SidechainParams](#Sidechain-params) for details)
2. newCommitteePubKeys - list of lexicographically sorted compressed ECDSA public keys of the next committee
   ```
   newCommitteePubKeys = sort([key1, key2, ..., keyN])
   keyN - 33 bytes compressed ecdsa public key of a committee member
   ```
3. previousMerkleRoot - last Merkle root inserted on chain, unless there is no Merkle root inserted yet.
   Submission of the handover certificate and submission of the transactions certificate are two separate actions and only the current committee can validate the current transaction's certificate. By referencing the previous Merkle root we enforce submission of all past transactions certificates before handing over control to the next committee.
4. sidechainEpoch - sidechain epoch for which the certificate is constructed. Its purpose is to prevent replay attacks (that rely on submitting the same signature multiple times), since the signature for each epoch will be different.

To obtain the ECDSA signature for the `UpdateCommitteeMessage`, it has to be first converted to bytes and then hashed.

The current implementation uses blake2b for hashing and CBOR for obtaining the byte representation.
To obtain the CBOR representation, the data is first converted into plutus datum primitives and then converted into final CBOR form following the datum encoding rules (see https://github.com/input-output-hk/plutus/blob/b4adeb6cb5d4fbb4f94cf2d65e6ac961534c4fcd/plutus-core/plutus-core/src/PlutusCore/Data.hs#L33-L40 for more details).

The final version of the signature looks as follows:

`signature = ecdsa.sign(data: blake2b(cbor(UpdateCommitteeMessage)), key: committeeMemberPrvKey)`

## Transactions certificate

Message to be signed:

```scala
final private case class MerkleRootInsertionMessage(
  sidechainParams: SidechainParams,//1
  outgoingTransactionsBatch: RootHash,//2
  previousOutgoingTransactionsBatch: Option[RootHash]//3
)
```

where:

1. sidechainParams - a structure that uniquely identifies a given sidechain (see [SidechainParams](#Sidechain-params) for details)
2. outgoingTransactionsBatch - Merkle root of all the transactions that happened during the given epoch
3. previousOutgoingTransactionsBatch - last Merkle root inserted on chain, unless there is no Merkle root inserted yet
   A reference to the previous Merkle root is required so that Plutus scripts can prevent insertion of newer transaction certificates when the previous one was not submitted yet. The same information is also included in the `outgoingTransactionsBatch` Merkle root, but it is not directly accessible.

The Merkle tree has to be constructed in the exact same way as it is done by the [following Merkle tree implementation](../../modules/sidechain/src/main/scala/io/iohk/scevm/sidechain/transactions/merkletree/MerkleTree.scala).

Entries in the tree should be calculated as follows:

```scala
final case class MerkleTreeEntry(
  index: OutgoingTxId//1,
  amount: Token//2,
  recipient: OutgoingTxRecipient//3,
  previousOutgoingTransactionsBatch: Option[RootHash]//4
)
```

where:

1. index - 32-bit unsigned integer, used to provide uniqueness among transactions within the tree
2. amount - 256-bit unsigned integer that represents the amount of tokens being sent out of the bridge
3. recipient - arbitrary length bytestring that represents a decoded bech32 cardano address
4. previousOutgoingTransactionsBatch - last Merkle root inserted on chain, unless there is no Merkle root inserted yet
   The reference to the previous Merkle root is necessary so that each obtained Merkle root is unique, even if they contain exactly the same transactions.

## Sidechain params

`SidechainParams` is a structure that uniquely identifies a given sidechain. In addition, it also contains some operational parameters that were set during the sidechain inception.

```scala
final case class SidechainParams(
    chainId: ChainId,
    genesisHash: BlockHash,
    genesisUtxo: UtxoId,
    thresholdNumerator: Int,
    thresholdDenominator: Int
)
```
