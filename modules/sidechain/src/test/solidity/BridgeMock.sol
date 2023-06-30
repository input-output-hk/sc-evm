// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.0;

import { IBridge } from "interfaces/IBridge.sol";

contract BridgeMock is IBridge {

  function lock(bytes calldata  /* recipient */) external payable override returns (uint256){
    return 1;
  }

  function unlock(bytes calldata /* txId */, address payable /* recipient */, uint256 /* amount */)
    external pure override returns (bool){
    return true;
  }

  function outgoingTransactions(uint256) external pure override returns (OutgoingTransaction[] memory) {
    OutgoingTransaction[] memory ret = new OutgoingTransaction[](1);
    ret[0] = OutgoingTransaction({amount: 10, recipient: hex"61626364", txIndex: 1, originalAmount: 10});
    return ret;
  }

  function getHandoverSignatures(uint256 /* epoch */, address[] calldata /* validators */) external pure returns (AddressWithSignature[] memory) {
    return new AddressWithSignature[](0);
  }

  function getOutgoingTransactionSignatures(uint256 /* epoch */, address[] calldata /* validators */) external pure returns (AddressWithSignature[] memory) {
    return new AddressWithSignature[](0);
  }

  function getCheckpointSignatures(
    uint256 /* epoch */,
    address[] calldata /* validators */
  ) external pure override returns (AddressWithSignature[] memory) {
    return new AddressWithSignature[](0);
  }


  function incomingTransactionHandled(bytes calldata txId) external pure override returns (bool) {
    bytes32 hash = keccak256(txId);
    bytes32 expected = keccak256(new bytes(0));
    return hash == expected;
  }

  function getLastProcessedIncomingTransaction() external pure returns (bytes memory) {
    return "dead:123";
  }

  function sign(
    bytes calldata handoverSignature,
    SignedEpochData calldata /* signedData */
  ) external returns (bool) {
    emit HandoverSignedEvent(msg.sender, handoverSignature, 0);
    return true;
  }

  function currentEpoch() external pure returns (uint256) {
    return 0;
  }

  function currentSlot() external pure returns (uint256) {
    return 0;
  }

  function epochPhase() external pure returns (EpochPhase) {
    return EpochPhase.Regular;
  }

  function getPreviousTxsBatchMerkleRootChainEntry() external pure returns (MerkleRootChainEntryWithEpoch memory) {
    return MerkleRootChainEntryWithEpoch("11111111111111111111111111111111", 0, 0);
  }

  function getMerkleRootChainEntry(uint256 /* epoch */) external pure override returns (MerkleRootChainEntry memory) {
    return MerkleRootChainEntry("11111111111111111111111111111111", 0);
  }

  function getCheckpointBlock(uint256 /* epoch */) external pure override returns (CheckpointBlock memory) {
    return CheckpointBlock(0x0000000000000000000000000000000000000000000000000000000000000000, 0);
  }
}
