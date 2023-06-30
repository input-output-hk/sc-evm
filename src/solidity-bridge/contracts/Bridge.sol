// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.4;

import "./interfaces/IBridge.sol";

/**
* @title SideChain {Bridge} Contract
*
*  @dev a PoC contract to see if the bridge can access the information about locked funds
*/
contract Bridge is IBridge {

  // 1 mainchain FUEL = TOKEN_CONVERSION_RATE sidechain FUEL
  uint256 public constant TOKEN_CONVERSION_RATE = 10**9;


  // these variables are read only and their value is injected by SC EVM
  uint256 override public currentEpoch;
  uint256 override public currentSlot;
  EpochPhase override public epochPhase;

  // this variable initial value should be set in the genesis
  uint256 currentTxsBatchMRHEpoch = 0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff;

  // storage variables
  bytes public lastProcessedTxId;
  mapping(uint256 => OutgoingTransaction[]) public transactions;
  mapping(uint256 => mapping(address => bytes)) public handoverSignatures;
  mapping(uint256 => mapping(address => bytes)) public transactionBatchSignatures;
  mapping(uint256 => mapping(address => bytes)) public checkpointSignatures;
  mapping(bytes32 => bool) handledIncomingTransactions;

  mapping(uint256 => MerkleRootChainEntry) public merkleRootChain;
  mapping(uint256 => CheckpointBlock) public checkpoints;

  /**
  * @notice Returns true if `account` is a contract and `msg.sender` is
    * not a proxy.
    *
    */
  modifier notContract() {
    require(!isContract(msg.sender), "Only direct calls are allowed");
    require(msg.sender == tx.origin, "No Proxy!");
    _;
  }

  /**
    * @notice Returns true if `msg.sender` is a coinbase account.
    *
    */
  modifier isCoinbaseAccount() {
    require(msg.sender == block.coinbase, "Not Coinbase Account!");
    _;
  }

  /**
  * @dev Returns true if `account` is a contract.
    *
    * [IMPORTANT]
    * ====
    * It is unsafe to assume that an address for which this function returns
    * false is an externally-owned account (EOA) and not a contract.
    *
    * Among others, `isContract` will return false for the following
    * types of addresses:
    *
    *  - an externally-owned account
    *  - a contract in construction
    *  - an address where a contract will be created
    *  - an address where a contract lived, but was destroyed
    * ====
    *
    * [IMPORTANT]
    * ====
    * You shouldn't rely on `isContract` to protect against flash loan attacks!
    *
    * Preventing calls from contracts is highly discouraged. It breaks composability, breaks support for smart wallets
    * like Gnosis Safe, and does not provide security since it can be circumvented by calling from a contract
    * constructor.
    * ====
    */
  function isContract(address account) internal view returns (bool) {
    // This method relies on extcodesize/address.code.length, which returns 0
    // for contracts in construction, since the code is only stored at the end
    // of the constructor execution.

    return account.code.length > 0;
  }

  /**
  * @dev See (IBridge - lock) function for details.
    *
    *
    * @notice emits `TokensLockedEvent` event
    */
  function lock(bytes calldata _recipient) external payable override returns (uint256) {
    require(msg.value > 0, "Bridge: amount should be strictly positive");
    require(msg.value % TOKEN_CONVERSION_RATE == 0, "Bridge: value not multiple of TOKEN_CONVERSION_RATE");

    uint256 batch;
    if(epochPhase == EpochPhase.Regular) {
      batch = currentEpoch;
    } else {
      batch = currentEpoch + 1;
    }

    uint256 mainchainAmount = msg.value / TOKEN_CONVERSION_RATE;
    uint256 txIndex = transactions[batch].length;
    transactions[batch].push(OutgoingTransaction({amount : mainchainAmount, recipient : _recipient, txIndex: txIndex, originalAmount: msg.value}));

    emit TokensLockedEvent(
      msg.sender,
      msg.value,
      _recipient,
      _recipient,
      txIndex
    );

    return txIndex;
  }

  /**
  * @dev See (IBridge - unlock) function for details.
    *
    * Requirements:
    * - Caller should not be contract,
    * - Caller should not be proxy
    *
    *
    * @notice emits `IncomingTransactionHandledEvent` event
    */
  function unlock(bytes calldata txId, address payable recipient, uint256 mainchainAmount) external notContract() isCoinbaseAccount() override returns (bool) {
    uint256 sidechainAmount = mainchainAmount * TOKEN_CONVERSION_RATE;

    require(sidechainAmount <= balance(), "Not Enough Funds!");
    require(tx.origin == msg.sender, "Only direct calls are allowed");

    bytes32 hash = keccak256(txId);
    require(handledIncomingTransactions[hash] == false, "This transaction has already been processed");

    handledIncomingTransactions[hash] = true;
    lastProcessedTxId = txId;

    if(!isContract(recipient)) {
      recipient.transfer(sidechainAmount);
      emit IncomingTransactionHandledEvent(
        txId,
        recipient,
        sidechainAmount,
        IncomingTransactionResult.Success
      );
      return true;
    } else {
      emit IncomingTransactionHandledEvent(
        txId,
        recipient,
        sidechainAmount,
        IncomingTransactionResult.ErrorContractRecipient
      );
      return false;
    }
  }

  function outgoingTransactions(uint256 epoch) external view override returns (OutgoingTransaction[] memory) {
    return transactions[epoch];
  }

  function sign(
    bytes calldata handoverSignature,
    SignedEpochData calldata signedData
  ) external isCoinbaseAccount() override returns (bool) {
    require(epochPhase == EpochPhase.Handover, "Outside of the handover phase");

    if(signedData.txsBatchMRH != 0) {
      if (currentTxsBatchMRHEpoch != currentEpoch) {
        merkleRootChain[currentEpoch] = MerkleRootChainEntry(signedData.txsBatchMRH, currentTxsBatchMRHEpoch);
        currentTxsBatchMRHEpoch = currentEpoch;
      } else {
        require(
          signedData.txsBatchMRH == merkleRootChain[currentTxsBatchMRHEpoch].rootHash,
          "sign with different hash within same epoch"
        );
      }

      transactionBatchSignatures[currentEpoch][block.coinbase] = signedData.outgoingTransactionSignature;
      emit OutgoingTransactionsSignedEvent(block.coinbase, signedData.outgoingTransactionSignature, signedData.txsBatchMRH);
    } else {
      merkleRootChain[currentEpoch] = MerkleRootChainEntry(0, currentTxsBatchMRHEpoch);
    }

    if(signedData.checkpointBlock.hash != 0) {
      CheckpointBlock memory currentCheckpoint = checkpoints[currentEpoch];
      if (currentCheckpoint.hash == 0x0) {
        checkpoints[currentEpoch] = signedData.checkpointBlock;
      } else {
        require(
          signedData.checkpointBlock.hash == currentCheckpoint.hash && signedData.checkpointBlock.number == currentCheckpoint.number,
          "sign with different checkpoint within same epoch"
        );
      }

      checkpointSignatures[currentEpoch][block.coinbase] = signedData.checkpointSignature;
      emit CheckpointBlockSignedEvent(block.coinbase, signedData.checkpointSignature, signedData.checkpointBlock);
    }


    handoverSignatures[currentEpoch][block.coinbase] = handoverSignature;
    emit HandoverSignedEvent(block.coinbase, handoverSignature, currentEpoch);
    return true;
  }


  function getHandoverSignatures(
    uint256 epoch,
    address[] calldata validators
  ) external view override returns (AddressWithSignature[] memory) {
    return getSignaturesFromMapping(epoch, validators, handoverSignatures);
  }

  function getOutgoingTransactionSignatures(
    uint256 epoch,
    address[] calldata validators
  ) external view override returns (AddressWithSignature[] memory) {
    return getSignaturesFromMapping(epoch, validators, transactionBatchSignatures);
  }

  function getCheckpointSignatures(
    uint256 epoch,
    address[] calldata validators
  ) external view override returns (AddressWithSignature[] memory) {
    return getSignaturesFromMapping(epoch, validators, checkpointSignatures);
  }


  function getSignaturesFromMapping(
    uint256 epoch,
    address[] calldata validators,
    mapping(uint256 => mapping(address => bytes)) storage signatureMapping
  ) private view returns (AddressWithSignature[] memory) {
    uint256 length = 0;
    mapping(address => bytes) storage epochSignatures = signatureMapping[epoch];
    for(uint256 i = 0; i < validators.length; i++) {
      if(epochSignatures[validators[i]].length > 0) {
        length++;
      }
    }
    AddressWithSignature[] memory ret = new AddressWithSignature[](length);
    uint256 retIndex = 0;
    for(uint256 i = 0; i < validators.length; i++) {
      bytes memory signature = epochSignatures[validators[i]];
      if(signature.length > 0) {
        ret[retIndex] = AddressWithSignature({ validator: validators[i], signature: signature });
        retIndex++;
      }
    }
    return ret;
  }

  /**
   * @dev Returns bridge balance.
    *
    */
  function balance() public view returns (uint256) {
    return address(this).balance;
  }

  function incomingTransactionHandled(bytes calldata txId) external view override returns (bool) {
    bytes32 hash = keccak256(txId);
    return handledIncomingTransactions[hash];
  }

  function getLastProcessedIncomingTransaction() external view override returns (bytes memory) {
    return lastProcessedTxId;
  }

  function getPreviousTxsBatchMerkleRootChainEntry() external view override returns (MerkleRootChainEntryWithEpoch memory) {
    if(currentEpoch==currentTxsBatchMRHEpoch) {
      uint256 epoch = merkleRootChain[currentTxsBatchMRHEpoch].previousEntryEpoch;
      MerkleRootChainEntry memory entry = merkleRootChain[epoch];
      return MerkleRootChainEntryWithEpoch(entry.rootHash, entry.previousEntryEpoch, epoch);
    } else {
      MerkleRootChainEntry memory entry = merkleRootChain[currentTxsBatchMRHEpoch];
      return MerkleRootChainEntryWithEpoch(entry.rootHash, entry.previousEntryEpoch, currentTxsBatchMRHEpoch);
    }
  }

  function getMerkleRootChainEntry(uint256 epoch) external view override returns (MerkleRootChainEntry memory) {
    return merkleRootChain[epoch];
  }

  function getCheckpointBlock(uint256 epoch) external view override returns (CheckpointBlock memory) {
    return checkpoints[epoch];
  }
}
