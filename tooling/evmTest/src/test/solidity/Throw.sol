// SPDX-License-Identifier: UNLICENSED
pragma solidity ^0.8.0;

contract Throw {
    function justThrow() pure public {
        assert(false);
    }
}
