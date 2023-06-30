// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.0;

contract BlockContextContract {

  event Number(uint256) anonymous;
  event Bytes(bytes32) anonymous;

  function checkBlockNumber() public returns (bool) {
    emit Number(block.number);
    return true;
  }

  function checkBlockTimestamp() public returns (bool) {
    emit Number(block.timestamp);
    return true;
  }

  function checkBlockHash() public returns (bool) {
    emit Bytes(blockhash(block.number - 1));
    return true;
  }
}
