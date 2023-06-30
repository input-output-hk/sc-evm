// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.0;

contract IndirectCall {
    function indirectCall (address contractAddress, bytes calldata payload) external {
        (bool success, bytes memory returnData) = contractAddress.call(payload);
        if(!success) {
            /* forward the error */
            assembly {
                revert(add(32, returnData), mload(returnData))
            }
        }
    }
}
