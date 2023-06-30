// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.4;

import "../Bridge.sol";
import "../interfaces/IBridge.sol";

contract BridgeCaller {
  // mock function - testing purposes
  function contractCall(
    Bridge _bridge,
    bytes calldata txId,
    address payable recipient,
    uint256 amount
  ) public returns (bool) {
    (bool b) = _bridge.unlock(txId, recipient, amount);
    return b;
  }
}
