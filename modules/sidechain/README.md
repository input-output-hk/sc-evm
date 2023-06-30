# Sidechain module

This module contains all the code specific to sidechain mode

## Compiling the bridge contract

requirements: solidity 0.8+

Compile the contract with

```
solc bridge.sol --bin-runtime --optimize
```

This will output the runtime bytecode (without the constructor code) as hexadecimal. This can be used inside the
genesis file.

```commandline
std //automation/jobs/update-genesis:run
```

This automation isn't 100% perfect yet, because there are variables initialized in contract storage.
If new variables are introduced into the contract, there is necessity to check before and after update
mapping of variables into memory slots:

```commandline
solc --storage-layout ./src/solidity-bridge/contracts/Bridge.sol
```

and manual update of genesis files.

## Initialize trustless-sidechain sub-module

```shell
git submodule update --init --recursive src/trustless-sidechain
```
