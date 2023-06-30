// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.4;

/**
 * @title {IBridge} Interface
 *
 */
interface IBridge {

  struct OutgoingTransaction {
    uint256 amount;
    bytes recipient;
    uint256 txIndex;
    uint256 originalAmount;
  }

  struct IncomingTransaction {
    uint256 amount;
    address payable recipient;
    bytes txId;
  }

  struct AddressWithSignature {
    address validator;
    bytes signature;
  }

  struct MerkleRootChainEntry {
    bytes32 rootHash;
    uint256 previousEntryEpoch;
  }

  struct MerkleRootChainEntryWithEpoch {
    bytes32 rootHash;
    uint256 previousEntryEpoch;
    uint256 epoch;
  }

  struct CheckpointBlock {
    bytes32 hash;
    uint256 number;
  }

  struct SignedEpochData {
    bytes32 txsBatchMRH;
    CheckpointBlock checkpointBlock;
    bytes outgoingTransactionSignature;
    bytes checkpointSignature;
  }

  enum EpochPhase { Regular, ClosedTransactionBatch, Handover }

  enum IncomingTransactionResult { Success, ErrorContractRecipient }

  event IncomingTransactionHandledEvent(
    bytes txId,
    address indexed recipient,
    uint256 amount,
    IncomingTransactionResult result
  );

  event TokensLockedEvent(
    address indexed fromAddr,
    uint256 amount,
    bytes mainChainRecipient,
    bytes indexed indexedMainChainRecipient,
    uint256 txIndex
  );

  event HandoverSignedEvent(
    address validator,
    bytes signature,
    uint256 sidechainEpoch
  );

  event OutgoingTransactionsSignedEvent(
    address validator,
    bytes signature,
    bytes32 merkleRootHash
  );

  event CheckpointBlockSignedEvent(
    address validator,
    bytes signature,
    CheckpointBlock block
  );

  /**
  * @dev Receives tokens to be sent to the main chain. Tokens are locked and
    * will only be able to be released when an incoming transaction unlocks them.
    * Returns index of the transaction within the current batch.
    *
    * @param _recipient main chain recipient passed as bech32 decoded cardano address
    * e.g. caadf8ad86e61a10f4b4e14d51acf1483dac59c9e90cbca5eda8b840
    *
    * Warning: Currently there is no validation for the _recipient.
    *          Passing invalid value will result in irreversible lost of funds!
    */
  function lock(bytes calldata _recipient) external payable returns (uint256);

  /**
    * @dev Unlocks the tokens that were locked when crossed the bridge.
    * It's assumed that only the block producers will be able to call this function.
    *
    * @param txId transaction ID
    * @param recipient address of recipient
    * @param amount token amount that should be unlocked
    */
  function unlock(bytes calldata txId, address payable recipient, uint256 amount) external returns (bool);

  /**
    * @dev store an end of epoch signature in the contract. The signed epoch data can optionnally contains
    * outgoing transactions and/or checkpoint for that epoch.
    * It's assumed that only the block producers will be able to call this function.
    *
    * @param handoverSignature signature to include in the certificate
    */
  function sign(
    bytes calldata handoverSignature,
    SignedEpochData calldata signedData
  ) external returns (bool);

  /**
    * @dev get the handover signature for the given validators for this epoch
    * This will return a list of AddressWithSignature containing only the signature for the validator
    * that actually signed for this epoch.
    *
    * @param epoch epoch for which we want to get the signatures
    * @param validators list of validators to get
    */
  function getHandoverSignatures(uint256 epoch, address[] calldata validators) external view returns (AddressWithSignature[] memory);

  /**
  * @dev get the outgoing transaction signature for the given validators for this epoch
    * This will return a list of AddressWithSignature containing only the signature for the validator
    * that actually signed for this epoch.
    *
    * @param epoch epoch for which we want to get the signatures
    * @param validators list of validators to get
    */
  function getOutgoingTransactionSignatures(uint256 epoch, address[] calldata validators) external view returns (AddressWithSignature[] memory);

  /**
  * @dev get the outgoing checkpoints signature for the given validators for this epoch
    * This will return a list of AddressWithSignature containing only the signature for the validator
    * that actually signed for this epoch.
    *
    * @param epoch epoch for which we want to get the signatures
    * @param validators list of validators to get
    */
  function getCheckpointSignatures(uint256 epoch, address[] calldata validators) external view returns (AddressWithSignature[] memory);

  /**
    * @dev List the transactions to be sent to main chain in a given batch
    * Only the current and previous epoch are available.
    *
    * @param epoch epoch to get
    */
  function outgoingTransactions(uint256 epoch) external view returns (OutgoingTransaction[] memory);

  /**
  * @dev  Checks if an incoming transaction was already handled by the bridge
  *
  * @param txId identifier for the transaction
  */
  function incomingTransactionHandled(bytes calldata txId) external view returns (bool);

  /**
  * @dev Returns last handled incoming transaction
  */
  function getLastProcessedIncomingTransaction() external view returns (bytes memory);

  function currentEpoch() external view returns (uint256);
  function currentSlot() external view returns (uint256);
  function epochPhase() external view returns (EpochPhase);

  /**
  * @dev returns the latest transactions batch merkle root chain entry from some past epoch including that epoch.
  * the contract of 'sign' and 'getPreviousTxsBatchMerkleRootHash' is that:
  1) within same epoch 'sign' can be called only with the same txsMerkleRootHash
  2) if 'getPreviousTxsBatchMerkleRootHash' is called before 'sign' in current epoch,
     then it returns the last value of txsMerkleRootHash 'sign' was called with
  3) if 'getPreviousTxsBatchMerkleRootHash' is called after 'sign' in current epoch,
     then it returns the second last different value of txsMerkleRootHash passed to 'sign'
  */
  function getPreviousTxsBatchMerkleRootChainEntry() external view returns (MerkleRootChainEntryWithEpoch memory);

  /**
  * @dev returns merkle root chain entry that was submitted for a given sidechain epoch.
  * Note that for the current epoch this method will return empty root when queried before the handover phase,
  * as the root is only available after it has been submitted by one of the committee members.
  * That is also why committee members should not rely on this method to obtain the current merkle root but rather use
  * getPreviousTxsBatchMerkleRootHash and calculate merkle root for the current epoch on their own.
  */
  function getMerkleRootChainEntry(uint256 epoch) external view returns (MerkleRootChainEntry memory);

  /**
  * @dev returns checkpoint block entry that was submitted for a given sidechain epoch.
  * Note that for the current epoch this method will return an empty block when queried before the handover phase,
  * as the checkpoint block is only available after it has been submitted by one of the committee members.
  */
  function getCheckpointBlock(uint256 epoch) external view returns (CheckpointBlock memory);
}
