// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.0;

contract GetNumberContract {

  function getBlockNumber() public view returns (uint256) {
    return block.number;
  }

}
