// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.0;

contract CallSelfDestruct {

  function callDestruct() public {
    CallSelfDestruct firstCall = CallSelfDestruct(this);
    firstCall.doSelfdestruct();

    CallSelfDestruct secondCall = CallSelfDestruct(this);
    secondCall.doSelfdestruct();
  }

  function doSelfdestruct() public {
    selfdestruct(payable(msg.sender));
  }

}
